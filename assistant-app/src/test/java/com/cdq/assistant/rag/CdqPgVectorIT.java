package com.cdq.assistant.rag;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode;
import com.cdq.assistant.rag.refresh.CdqKnowledgeDiffer;
import com.cdq.assistant.rag.refresh.CdqKnowledgeOperationException;
import com.cdq.assistant.rag.refresh.CdqKnowledgeScan;
import com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersion;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersionRepository;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus;
import com.cdq.assistant.rag.refresh.CdqKnowledgeView;
import com.cdq.assistant.rag.refresh.CdqKnowledgeWorkflow;
import com.cdq.assistant.rag.refresh.CdqWebsiteContentExtractor;
import com.cdq.assistant.rag.refresh.CdqWebsitePage;
import com.cdq.assistant.rag.refresh.JdbcCdqKnowledgeVersionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CdqPgVectorIT {

    private static final String IMAGE = "pgvector/pgvector:0.8.5-pg17-bookworm";
    private static final String SOURCE_ID = "cdq-fraud-guard";
    private static final URI SOURCE_URL = URI.create("https://www.cdq.com/products/cdq-fraud-guard");
    private static final UUID ACTIVE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-04T08:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-04T08:01:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cdq_assistant")
            .withUsername("cdq")
            .withPassword("cdq");

    private static final DeterministicEmbeddingModel EMBEDDING_MODEL = new DeterministicEmbeddingModel();

    private static JdbcTemplate jdbcTemplate;

    private static TransactionTemplate transactions;

    private static PgVectorCdqRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, EMBEDDING_MODEL)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
        transactions = new TransactionTemplate(new DataSourceTransactionManager((DataSource) dataSource));
        repository = new PgVectorCdqRepository(vectorStore, jdbcTemplate, transactions);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute(
                "ALTER TABLE cdq_knowledge_version DISABLE TRIGGER cdq_knowledge_prevent_truncate");
        try {
            jdbcTemplate.update("""
                    TRUNCATE TABLE cdq_knowledge_scan, cdq_knowledge_version, vector_store
                    RESTART IDENTITY
                    """);
        }
        finally {
            jdbcTemplate.execute(
                    "ALTER TABLE cdq_knowledge_version ENABLE TRIGGER cdq_knowledge_prevent_truncate");
        }
    }

    @Test
    void persistsOneActiveVersionOneOpenCandidateAndTheLatestScan() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CdqKnowledgeVersion active = version(
                CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID);
        CdqKnowledgeVersion candidate = version(
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "b".repeat(64), CANDIDATE_ID);
        CdqKnowledgeScan scan = new CdqKnowledgeScan(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                SOURCE_ID,
                Instant.parse("2026-08-04T08:02:00Z"),
                CdqKnowledgeScanOutcome.CHANGES_DETECTED,
                "b".repeat(64),
                CANDIDATE_ID,
                null);

        versions.insertVersion(active);
        versions.insertVersion(candidate);
        versions.insertScan(scan);

        assertThat(versions.findActive(SOURCE_ID)).contains(active);
        assertThat(versions.findOpenCandidate(SOURCE_ID)).contains(candidate);
        assertThat(versions.findVersion(ACTIVE_ID)).contains(active);
        assertThat(versions.findLatestScan(SOURCE_ID)).contains(scan);
        transactions.executeWithoutResult(status -> {
            assertThat(versions.findActiveForUpdate(SOURCE_ID)).contains(active);
            assertThat(versions.findOpenCandidateForUpdate(SOURCE_ID)).contains(candidate);
            assertThat(versions.findVersionForUpdate(CANDIDATE_ID)).contains(candidate);
        });
    }

    @Test
    void databaseRejectsDuplicateActiveAndOpenCandidateRows() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        versions.insertVersion(version(CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID));

        assertThatThrownBy(() -> versions.insertVersion(version(
                        CdqKnowledgeVersionStatus.ACTIVE,
                        "b".repeat(64),
                        UUID.fromString("00000000-0000-0000-0000-000000000004"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        versions.insertVersion(version(
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "c".repeat(64), CANDIDATE_ID));

        assertThatThrownBy(() -> versions.insertVersion(version(
                        CdqKnowledgeVersionStatus.APPROVED,
                        "d".repeat(64),
                        UUID.fromString("00000000-0000-0000-0000-000000000005"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDeletingTheSoleActiveVersion() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        versions.insertVersion(version(CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbcTemplate.update(
                        "DELETE FROM cdq_knowledge_version WHERE id = ?", ACTIVE_ID)))
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("cdq_knowledge_requires_active");
    }

    @Test
    void databaseRejectsTruncatingEstablishedVersionHistory() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        versions.insertVersion(version(CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "TRUNCATE TABLE cdq_knowledge_scan, cdq_knowledge_version"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23514"))
                .hasMessageContaining("cdq_knowledge_requires_active");
    }

    @Test
    void databaseRejectsTransitioningTheSoleActiveVersion() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        versions.insertVersion(version(CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> assertThat(versions.transition(
                                ACTIVE_ID,
                                CdqKnowledgeVersionStatus.ACTIVE,
                                CdqKnowledgeVersionStatus.INACTIVE,
                                Instant.parse("2026-08-04T09:00:00Z"),
                                null,
                                null))
                        .isEqualTo(1)))
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("cdq_knowledge_requires_active");
    }

    @Test
    void databaseAllowsAtomicActiveVersionHandover() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CdqKnowledgeVersion active = version(
                CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID);
        CdqKnowledgeVersion approved = version(
                CdqKnowledgeVersionStatus.APPROVED, "b".repeat(64), CANDIDATE_ID);
        Instant activatedAt = Instant.parse("2026-08-04T10:00:00Z");
        versions.insertVersion(active);
        versions.insertVersion(approved);

        transactions.executeWithoutResult(status -> {
            assertThat(versions.transition(
                            ACTIVE_ID,
                            CdqKnowledgeVersionStatus.ACTIVE,
                            CdqKnowledgeVersionStatus.INACTIVE,
                            activatedAt,
                            null,
                            null))
                    .isEqualTo(1);
            assertThat(versions.transition(
                            CANDIDATE_ID,
                            CdqKnowledgeVersionStatus.APPROVED,
                            CdqKnowledgeVersionStatus.ACTIVE,
                            activatedAt,
                            null,
                            null))
                    .isEqualTo(1);
        });

        assertThat(versions.findVersion(ACTIVE_ID).orElseThrow().status())
                .isEqualTo(CdqKnowledgeVersionStatus.INACTIVE);
        assertThat(versions.findActive(SOURCE_ID).orElseThrow().id()).isEqualTo(CANDIDATE_ID);
        assertThat(versions.findActive(SOURCE_ID).orElseThrow().activatedAt()).isEqualTo(activatedAt);
    }

    @Test
    void latestScanUsesDatabaseGeneratedSequenceForEqualTimestamps() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        Instant scannedAt = Instant.parse("2026-08-04T12:00:00Z");
        CdqKnowledgeScan first = new CdqKnowledgeScan(
                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                SOURCE_ID,
                scannedAt,
                CdqKnowledgeScanOutcome.UNCHANGED,
                "a".repeat(64),
                null,
                null);
        CdqKnowledgeScan second = new CdqKnowledgeScan(
                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                SOURCE_ID,
                scannedAt,
                CdqKnowledgeScanOutcome.UNCHANGED,
                "b".repeat(64),
                null,
                null);

        versions.insertScan(first);
        versions.insertScan(second);

        String firstSequence = scanSequence(first.id());
        String secondSequence = scanSequence(second.id());
        assertThat(firstSequence).isNotNull();
        assertThat(secondSequence).isNotNull();
        assertThat(Long.parseLong(secondSequence)).isGreaterThan(Long.parseLong(firstSequence));
        assertThat(versions.findLatestScan(SOURCE_ID)).contains(second);
    }

    @Test
    void databaseRejectsUnknownSourcesInvalidHashesAndNonScanFailureCodes() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);

        assertThatThrownBy(() -> versions.insertVersion(new CdqKnowledgeVersion(
                        ACTIVE_ID,
                        "other-source",
                        SOURCE_URL,
                        CdqKnowledgeVersionStatus.ACTIVE,
                        "content",
                        "a".repeat(64),
                        CAPTURED_AT,
                        CREATED_AT,
                        null,
                        null,
                        null,
                        null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> versions.insertVersion(
                        version(CdqKnowledgeVersionStatus.ACTIVE, "not-a-sha-256", ACTIVE_ID)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> versions.insertScan(new CdqKnowledgeScan(
                        UUID.fromString("00000000-0000-0000-0000-000000000006"),
                        SOURCE_ID,
                        Instant.parse("2026-08-04T08:03:00Z"),
                        CdqKnowledgeScanOutcome.FAILED,
                        null,
                        null,
                        CdqKnowledgeFailureCode.VERSION_NOT_FOUND)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardedTransitionMapsReviewTimeAndRejectsStaleState() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CdqKnowledgeVersion active = version(
                CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID);
        CdqKnowledgeVersion candidate = version(
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "b".repeat(64), CANDIDATE_ID);
        Instant reviewedAt = Instant.parse("2026-08-04T09:00:00Z");
        versions.insertVersion(active);
        versions.insertVersion(candidate);

        assertThat(versions.transition(
                        CANDIDATE_ID,
                        CdqKnowledgeVersionStatus.PENDING_REVIEW,
                        CdqKnowledgeVersionStatus.APPROVED,
                        reviewedAt,
                        "reviewed",
                        null))
                .isEqualTo(1);
        assertThat(versions.transition(
                        CANDIDATE_ID,
                        CdqKnowledgeVersionStatus.PENDING_REVIEW,
                        CdqKnowledgeVersionStatus.REJECTED,
                        reviewedAt,
                        "stale",
                        null))
                .isZero();
        assertThat(versions.findVersion(CANDIDATE_ID))
                .contains(new CdqKnowledgeVersion(
                        CANDIDATE_ID,
                        SOURCE_ID,
                        SOURCE_URL,
                        CdqKnowledgeVersionStatus.APPROVED,
                        "content-b",
                        "b".repeat(64),
                        CAPTURED_AT,
                        CREATED_AT,
                        reviewedAt,
                        "reviewed",
                        null,
                        null));
    }

    @Test
    void defersSupersededReferenceUntilReplacementIsInsertedInTheSameTransaction() {
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CdqKnowledgeVersion current = version(
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "b".repeat(64), CANDIDATE_ID);
        UUID replacementId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        CdqKnowledgeVersion replacement = version(
                CdqKnowledgeVersionStatus.PENDING_REVIEW, "c".repeat(64), replacementId);
        Instant supersededAt = Instant.parse("2026-08-04T11:00:00Z");
        versions.insertVersion(version(CdqKnowledgeVersionStatus.ACTIVE, "a".repeat(64), ACTIVE_ID));
        versions.insertVersion(current);

        transactions.executeWithoutResult(status -> {
            assertThat(versions.transition(
                            CANDIDATE_ID,
                            CdqKnowledgeVersionStatus.PENDING_REVIEW,
                            CdqKnowledgeVersionStatus.SUPERSEDED,
                            supersededAt,
                            null,
                            replacementId))
                    .isEqualTo(1);
            versions.insertVersion(replacement);
        });

        assertThat(versions.findVersion(CANDIDATE_ID).orElseThrow().supersededBy())
                .isEqualTo(replacementId);
        assertThat(versions.findOpenCandidate(SOURCE_ID)).contains(replacement);
    }

    @Test
    void migratesIngestsIdempotentlyReplacesOnlyItsSourceAndRetrievesBySimilarity() {
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'vector_store'
                          AND column_name = 'metadata'
                        """,
                        String.class))
                .isEqualTo("json");
        CdqKnowledgeLoader loader = new CdqKnowledgeLoader(
                new ClassPathResource("knowledge/cdq-fraud-guard.txt"),
                new ClassPathResource("knowledge/cdq-fraud-guard.source.json"));
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);
        CdqKnowledgeSnapshot snapshot = loader.load();

        assertThat(ingestor.ingest(snapshot)).isEqualTo(CdqIngestionOutcome.REPLACED);
        assertThat(countRows("cdq-fraud-guard")).isEqualTo(2);
        assertThat(distinctIds("cdq-fraud-guard")).isEqualTo(2);
        int embeddingCallsAfterFirstIngest = EMBEDDING_MODEL.calls();

        assertThat(ingestor.ingest(snapshot)).isEqualTo(CdqIngestionOutcome.SKIPPED);
        assertThat(countRows("cdq-fraud-guard")).isEqualTo(2);
        assertThat(EMBEDDING_MODEL.calls()).isEqualTo(embeddingCallsAfterFirstIngest);

        String retrieval = new CdqFraudGuardSearchTool(repository)
                .searchCdqFraudGuard("How does the Trust Score work?");
        assertThat(retrieval)
                .contains("Trust Score")
                .contains("sourceId: cdq-fraud-guard")
                .doesNotContain(CdqFraudGuardSearchTool.NO_RESULT);

        repository.replaceSource(
                "other-source",
                List.of(document(
                        "60c038cb-a299-3bec-a26a-9b8fbe2d099a",
                        "Unrelated shared vector data",
                        "other-source",
                        "other-hash",
                        0)));
        String changedHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        CdqKnowledgeSnapshot changedSnapshot = new CdqKnowledgeSnapshot(
                "cdq-fraud-guard",
                "https://www.cdq.com/products/cdq-fraud-guard",
                "2026-07-26T09:00:00Z",
                changedHash,
                "Trust Score replacement content",
                List.of(document(
                        "temporary",
                        "Trust Score replacement content",
                        "cdq-fraud-guard",
                        changedHash,
                        0)));

        assertThat(new CdqKnowledgeIngestor(repository).ingest(changedSnapshot))
                .isEqualTo(CdqIngestionOutcome.REPLACED);
        assertThat(countRows("cdq-fraud-guard")).isEqualTo(1);
        assertThat(distinctIds("cdq-fraud-guard")).isEqualTo(1);
        assertThat(countRows("other-source")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .isEqualTo(2);
        assertThat(repository.snapshotHashes("cdq-fraud-guard")).containsExactly(changedHash);
        assertThat(new CdqFraudGuardSearchTool(repository)
                        .searchCdqFraudGuard("Trust Score replacement")
                        .toLowerCase(Locale.ROOT))
                .contains("trust score replacement content");
    }

    @Test
    void failedApprovedIngestRollsBackVectorsAndBothVersionStates() {
        CdqKnowledgeSnapshot oldSnapshot = snapshot(
                "Old active Trust Score and fraud protection content. ".repeat(12),
                "2026-08-04T08:00:00Z");
        CdqKnowledgeSnapshot candidateSnapshot = snapshot(
                "Candidate provider-secret failure content for the new Trust Score. ".repeat(12),
                "2026-08-04T09:00:00Z");
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        PgVectorCdqRepository failingVectors = vectorRepository(
                new FailingEmbeddingModel("provider-secret failure"));
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(failingVectors);
        ingestor.ingest(oldSnapshot);
        versions.insertVersion(version(oldSnapshot, CdqKnowledgeVersionStatus.ACTIVE, ACTIVE_ID));
        versions.insertVersion(version(candidateSnapshot, CdqKnowledgeVersionStatus.APPROVED, CANDIDATE_ID));
        CdqKnowledgeWorkflow workflow = workflow(versions, ingestor);

        assertThatThrownBy(() -> workflow.ingest(CANDIDATE_ID))
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .hasMessage(CdqKnowledgeFailureCode.INGEST_UNAVAILABLE.name())
                .hasMessageNotContaining("provider-secret")
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(CdqKnowledgeFailureCode.INGEST_UNAVAILABLE);

        assertThat(failingVectors.snapshotHashes(SOURCE_ID)).containsExactly(oldSnapshot.snapshotHash());
        assertThat(versions.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::id)
                .isEqualTo(ACTIVE_ID);
        assertThat(versions.findVersion(CANDIDATE_ID)).get()
                .extracting(CdqKnowledgeVersion::status)
                .isEqualTo(CdqKnowledgeVersionStatus.APPROVED);
    }

    @Test
    void restartLoadsUiActivatedDatabaseContentInsteadOfBundledContent() {
        CdqKnowledgeSnapshot bundledSnapshot = snapshot(
                "Bundled Trust Score knowledge from the packaged application. ".repeat(12),
                "2026-07-26T08:22:11Z");
        CdqKnowledgeSnapshot approvedSnapshot = snapshot(
                "UI approved Trust Score knowledge stored in PostgreSQL. ".repeat(12),
                "2026-08-04T09:00:00Z");
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);
        CdqKnowledgeWorkflow firstApplication = workflow(versions, ingestor);
        firstApplication.initializeFrom(() -> bundledSnapshot);
        versions.insertVersion(version(
                approvedSnapshot, CdqKnowledgeVersionStatus.APPROVED, CANDIDATE_ID));
        firstApplication.ingest(CANDIDATE_ID);
        AtomicInteger bundledLoadsOnRestart = new AtomicInteger();

        CdqKnowledgeSnapshot restarted = workflow(versions, ingestor).initializeFrom(() -> {
            bundledLoadsOnRestart.incrementAndGet();
            return bundledSnapshot;
        });

        assertThat(bundledLoadsOnRestart).hasValue(0);
        assertThat(restarted.snapshotHash()).isEqualTo(approvedSnapshot.snapshotHash());
        assertThat(restarted.content()).isEqualTo(approvedSnapshot.content());
        assertThat(versions.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::id)
                .isEqualTo(CANDIDATE_ID);
        assertThat(repository.snapshotHashes(SOURCE_ID)).containsExactly(approvedSnapshot.snapshotHash());
    }

    @Test
    void startupHoldsTheActiveLockThroughVectorIngestionAndSerializesActivation() throws Exception {
        CdqKnowledgeSnapshot startupSnapshot = snapshot(
                "Startup-selected Trust Score knowledge from PostgreSQL. ".repeat(12),
                "2026-08-04T08:00:00Z");
        CdqKnowledgeSnapshot approvedSnapshot = snapshot(
                "Concurrently approved Trust Score knowledge from the UI. ".repeat(12),
                "2026-08-04T09:00:00Z");
        JdbcCdqKnowledgeVersionRepository databaseVersions =
                new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        CountDownLatch startupVectorEntered = new CountDownLatch(1);
        CountDownLatch allowStartupVectorCompletion = new CountDownLatch(1);
        CountDownLatch activationLockAttempted = new CountDownLatch(1);
        AtomicBoolean observeActivationLock = new AtomicBoolean();
        CdqKnowledgeVersionRepository observedVersions = new ActiveLockObservingRepository(
                databaseVersions, observeActivationLock, activationLockAttempted);
        CdqVectorRepository blockedVectors = new FirstReplacementBlockingRepository(
                repository, startupVectorEntered, allowStartupVectorCompletion);
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(blockedVectors);
        databaseVersions.insertVersion(version(
                startupSnapshot, CdqKnowledgeVersionStatus.ACTIVE, ACTIVE_ID));
        databaseVersions.insertVersion(version(
                approvedSnapshot, CdqKnowledgeVersionStatus.APPROVED, CANDIDATE_ID));
        CdqKnowledgeWorkflow workflow = workflow(observedVersions, ingestor);
        CdqKnowledgeIngestionRunner runner = new CdqKnowledgeIngestionRunner(
                workflow, () -> startupSnapshot);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> startup = null;
        Future<CdqKnowledgeView> activation = null;
        try {
            startup = executor.submit(() -> runner.run(new org.springframework.boot.DefaultApplicationArguments(
                    new String[0])));
            assertThat(startupVectorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            observeActivationLock.set(true);
            activation = executor.submit(() -> workflow.ingest(CANDIDATE_ID));
            assertThat(activationLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<CdqKnowledgeView> waitingActivation = activation;
            assertThatThrownBy(() -> waitingActivation.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowStartupVectorCompletion.countDown();
            startup.get(5, TimeUnit.SECONDS);
            activation.get(5, TimeUnit.SECONDS);

            String activeHash = databaseVersions.findActive(SOURCE_ID).orElseThrow().snapshotHash();
            assertThat(activeHash).isEqualTo(approvedSnapshot.snapshotHash());
            assertThat(blockedVectors.snapshotHashes(SOURCE_ID)).containsExactly(activeHash);
        }
        finally {
            allowStartupVectorCompletion.countDown();
            if (startup != null) {
                startup.cancel(true);
            }
            if (activation != null) {
                activation.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void activationDatabaseFailureAfterVectorReplacementRollsBackEverything() {
        CdqKnowledgeSnapshot oldSnapshot = snapshot(
                "Old active Trust Score knowledge before database failure. ".repeat(12),
                "2026-08-04T08:00:00Z");
        CdqKnowledgeSnapshot candidateSnapshot = snapshot(
                "Approved Trust Score knowledge whose activation will fail. ".repeat(12),
                "2026-08-04T09:00:00Z");
        JdbcCdqKnowledgeVersionRepository versions = new JdbcCdqKnowledgeVersionRepository(jdbcTemplate);
        new CdqKnowledgeIngestor(repository).ingest(oldSnapshot);
        versions.insertVersion(version(oldSnapshot, CdqKnowledgeVersionStatus.ACTIVE, ACTIVE_ID));
        versions.insertVersion(version(candidateSnapshot, CdqKnowledgeVersionStatus.APPROVED, CANDIDATE_ID));
        AtomicInteger completedReplacements = new AtomicInteger();
        CdqVectorRepository countedVectors = new CompletedReplacementCountingRepository(
                repository, completedReplacements);
        CdqKnowledgeWorkflow workflow = workflow(
                versions, new CdqKnowledgeIngestor(countedVectors));

        try {
            installCandidateActivationFailureTrigger();
            assertThatThrownBy(() -> workflow.ingest(CANDIDATE_ID))
                    .isInstanceOf(CdqKnowledgeOperationException.class)
                    .hasMessage(CdqKnowledgeFailureCode.INGEST_UNAVAILABLE.name());

            assertThat(completedReplacements).hasValue(1);
            assertThat(countedVectors.snapshotHashes(SOURCE_ID)).containsExactly(oldSnapshot.snapshotHash());
            assertThat(versions.findActive(SOURCE_ID)).get()
                    .extracting(CdqKnowledgeVersion::id)
                    .isEqualTo(ACTIVE_ID);
            assertThat(versions.findVersion(CANDIDATE_ID)).get()
                    .extracting(CdqKnowledgeVersion::status)
                    .isEqualTo(CdqKnowledgeVersionStatus.APPROVED);
        }
        finally {
            removeCandidateActivationFailureTrigger();
        }
    }

    private static PgVectorCdqRepository vectorRepository(EmbeddingModel embeddingModel) {
        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
        return new PgVectorCdqRepository(vectorStore, jdbcTemplate, transactions);
    }

    private static CdqKnowledgeWorkflow workflow(
            CdqKnowledgeVersionRepository versions, CdqKnowledgeIngestor ingestor) {
        return new CdqKnowledgeWorkflow(
                SOURCE_ID,
                SOURCE_URL,
                () -> new CdqWebsitePage("<html></html>", CAPTURED_AT),
                new CdqWebsiteContentExtractor(),
                versions,
                new CdqKnowledgeDiffer(),
                transactions,
                Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC),
                ingestor,
                new CdqKnowledgeSnapshotFactory());
    }

    private static void installCandidateActivationFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_test_candidate_activation()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF OLD.id = '00000000-0000-0000-0000-000000000002'::uuid
                            AND NEW.status = 'ACTIVE' THEN
                        RAISE EXCEPTION 'forced test activation failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_test_candidate_activation
                BEFORE UPDATE ON cdq_knowledge_version
                FOR EACH ROW EXECUTE FUNCTION fail_test_candidate_activation()
                """);
    }

    private static void removeCandidateActivationFailureTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_test_candidate_activation ON cdq_knowledge_version");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_test_candidate_activation()");
    }

    private static CdqKnowledgeSnapshot snapshot(String content, String capturedAt) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new CdqKnowledgeSnapshotFactory().create(
                SOURCE_URL.toString(), capturedAt, sha256(bytes), bytes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static CdqKnowledgeVersion version(
            CdqKnowledgeSnapshot snapshot, CdqKnowledgeVersionStatus status, UUID id) {
        Instant capturedAt = Instant.parse(snapshot.capturedAt());
        return new CdqKnowledgeVersion(
                id,
                SOURCE_ID,
                SOURCE_URL,
                status,
                snapshot.content(),
                snapshot.snapshotHash(),
                capturedAt,
                capturedAt,
                status == CdqKnowledgeVersionStatus.APPROVED ? capturedAt : null,
                status == CdqKnowledgeVersionStatus.APPROVED ? "approved in the UI" : null,
                status == CdqKnowledgeVersionStatus.ACTIVE ? capturedAt : null,
                null);
    }

    private static int countRows(String sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Integer.class,
                sourceId);
    }

    private static int distinctIds(String sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT id) FROM vector_store WHERE metadata->>'sourceId' = ?",
                Integer.class,
                sourceId);
    }

    private static String scanSequence(UUID scanId) {
        return jdbcTemplate.queryForObject(
                "SELECT to_jsonb(scan)->>'scan_sequence' FROM cdq_knowledge_scan scan WHERE id = ?",
                String.class,
                scanId);
    }

    private static CdqKnowledgeVersion version(
            CdqKnowledgeVersionStatus status, String snapshotHash, UUID id) {
        return new CdqKnowledgeVersion(
                id,
                SOURCE_ID,
                SOURCE_URL,
                status,
                "content-" + snapshotHash.charAt(0),
                snapshotHash,
                CAPTURED_AT,
                CREATED_AT,
                null,
                null,
                status == CdqKnowledgeVersionStatus.ACTIVE ? CREATED_AT : null,
                null);
    }

    private static Document document(
            String id, String text, String sourceId, String snapshotHash, int chunkIndex) {
        return new Document(
                id,
                text,
                Map.of(
                        "sourceId", sourceId,
                        "sourceUrl", "https://example.test/" + sourceId,
                        "capturedAt", "2026-07-26T08:22:11Z",
                        "snapshotHash", snapshotHash,
                        "chunkIndex", chunkIndex));
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            List<Embedding> embeddings = request.getInstructions().stream()
                    .map(DeterministicEmbeddingModel::vector)
                    .map(output -> new Embedding(output, 0))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            calls.incrementAndGet();
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        int calls() {
            return calls.get();
        }

        private static float[] vector(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            float[] vector = new float[1024];
            vector[0] = normalized.contains("trust score") ? 1.0f : 0.0f;
            vector[1] = normalized.contains("fraud") ? 1.0f : 0.0f;
            vector[2] = normalized.contains("bank account") ? 1.0f : 0.0f;
            vector[3] = normalized.contains("integration") ? 1.0f : 0.0f;
            if (vector[0] == 0.0f && vector[1] == 0.0f && vector[2] == 0.0f && vector[3] == 0.0f) {
                vector[1023] = 1.0f;
            }
            return vector;
        }
    }

    private static final class FailingEmbeddingModel implements EmbeddingModel {

        private final String failureMarker;
        private final DeterministicEmbeddingModel delegate = new DeterministicEmbeddingModel();

        private FailingEmbeddingModel(String failureMarker) {
            this.failureMarker = failureMarker;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (request.getInstructions().stream().anyMatch(text -> text.contains(failureMarker))) {
                throw new IllegalStateException("provider-secret embedding diagnostic");
            }
            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            if (document.getText().contains(failureMarker)) {
                throw new IllegalStateException("provider-secret embedding diagnostic");
            }
            return delegate.embed(document);
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }
    }

    private static final class FirstReplacementBlockingRepository implements CdqVectorRepository {

        private final CdqVectorRepository delegate;
        private final CountDownLatch replacementEntered;
        private final CountDownLatch allowReplacementCompletion;
        private final AtomicInteger replacements = new AtomicInteger();

        private FirstReplacementBlockingRepository(
                CdqVectorRepository delegate,
                CountDownLatch replacementEntered,
                CountDownLatch allowReplacementCompletion) {
            this.delegate = delegate;
            this.replacementEntered = replacementEntered;
            this.allowReplacementCompletion = allowReplacementCompletion;
        }

        @Override
        public Set<String> snapshotHashes(String sourceId) {
            return delegate.snapshotHashes(sourceId);
        }

        @Override
        public void replaceSource(String sourceId, List<Document> documents) {
            if (replacements.incrementAndGet() == 1) {
                replacementEntered.countDown();
                try {
                    if (!allowReplacementCompletion.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release startup vectors");
                    }
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while pausing startup vectors", exception);
                }
            }
            delegate.replaceSource(sourceId, documents);
        }

        @Override
        public List<Document> search(
                String query, int topK, double similarityThreshold, String sourceId) {
            return delegate.search(query, topK, similarityThreshold, sourceId);
        }
    }

    private static final class CompletedReplacementCountingRepository implements CdqVectorRepository {

        private final CdqVectorRepository delegate;
        private final AtomicInteger completedReplacements;

        private CompletedReplacementCountingRepository(
                CdqVectorRepository delegate, AtomicInteger completedReplacements) {
            this.delegate = delegate;
            this.completedReplacements = completedReplacements;
        }

        @Override
        public Set<String> snapshotHashes(String sourceId) {
            return delegate.snapshotHashes(sourceId);
        }

        @Override
        public void replaceSource(String sourceId, List<Document> documents) {
            delegate.replaceSource(sourceId, documents);
            completedReplacements.incrementAndGet();
        }

        @Override
        public List<Document> search(
                String query, int topK, double similarityThreshold, String sourceId) {
            return delegate.search(query, topK, similarityThreshold, sourceId);
        }
    }

    private static final class ActiveLockObservingRepository implements CdqKnowledgeVersionRepository {

        private final CdqKnowledgeVersionRepository delegate;
        private final AtomicBoolean observeActiveLock;
        private final CountDownLatch activeLockAttempted;

        private ActiveLockObservingRepository(
                CdqKnowledgeVersionRepository delegate,
                AtomicBoolean observeActiveLock,
                CountDownLatch activeLockAttempted) {
            this.delegate = delegate;
            this.observeActiveLock = observeActiveLock;
            this.activeLockAttempted = activeLockAttempted;
        }

        @Override
        public Optional<CdqKnowledgeVersion> findActive(String sourceId) {
            return delegate.findActive(sourceId);
        }

        @Override
        public Optional<CdqKnowledgeVersion> findActiveForUpdate(String sourceId) {
            if (observeActiveLock.get()) {
                activeLockAttempted.countDown();
            }
            return delegate.findActiveForUpdate(sourceId);
        }

        @Override
        public Optional<CdqKnowledgeVersion> findOpenCandidate(String sourceId) {
            return delegate.findOpenCandidate(sourceId);
        }

        @Override
        public Optional<CdqKnowledgeVersion> findOpenCandidateForUpdate(String sourceId) {
            return delegate.findOpenCandidateForUpdate(sourceId);
        }

        @Override
        public Optional<CdqKnowledgeVersion> findVersion(UUID id) {
            return delegate.findVersion(id);
        }

        @Override
        public Optional<CdqKnowledgeVersion> findVersionForUpdate(UUID id) {
            return delegate.findVersionForUpdate(id);
        }

        @Override
        public Optional<CdqKnowledgeScan> findLatestScan(String sourceId) {
            return delegate.findLatestScan(sourceId);
        }

        @Override
        public void insertVersion(CdqKnowledgeVersion version) {
            delegate.insertVersion(version);
        }

        @Override
        public void insertScan(CdqKnowledgeScan scan) {
            delegate.insertScan(scan);
        }

        @Override
        public int transition(
                UUID id,
                CdqKnowledgeVersionStatus expected,
                CdqKnowledgeVersionStatus target,
                Instant eventTime,
                String reviewComment,
                UUID supersededBy) {
            return delegate.transition(
                    id, expected, target, eventTime, reviewComment, supersededBy);
        }
    }
}
