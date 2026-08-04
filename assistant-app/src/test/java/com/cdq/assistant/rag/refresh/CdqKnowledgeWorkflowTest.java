package com.cdq.assistant.rag.refresh;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.cdq.assistant.rag.CdqKnowledgeIngestor;
import com.cdq.assistant.rag.CdqKnowledgeSnapshotFactory;
import com.cdq.assistant.rag.CdqVectorRepository;

import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.INGEST_UNAVAILABLE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.SOURCE_CONTENT_INVALID;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.SOURCE_UNAVAILABLE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.VERSION_NOT_FOUND;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.VERSION_STATE_CONFLICT;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.CHANGES_DETECTED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.FAILED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.UNCHANGED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.ACTIVE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.APPROVED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.INACTIVE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.PENDING_REVIEW;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.REJECTED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.SUPERSEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdqKnowledgeWorkflowTest {

    private static final String SOURCE_ID = "cdq-fraud-guard";
    private static final URI SOURCE_URL = URI.create("https://www.cdq.com/products/cdq-fraud-guard");
    private static final Instant ACTIVE_CAPTURED_AT = Instant.parse("2026-08-03T08:00:00Z");
    private static final Instant REMOTE_CAPTURED_AT = Instant.parse("2026-08-04T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final CdqWebsiteContentExtractor extractor = new CdqWebsiteContentExtractor();
    private final String approvedHtml = fixture("cdq-fraud-guard-page.html");
    private final String changedHtml = fixture("cdq-fraud-guard-page-changed.html");
    private final CdqExtractedContent approved = extractor.extract(approvedHtml);
    private final CdqExtractedContent changed = extractor.extract(changedHtml);

    private FakeCdqKnowledgeVersionRepository repository;
    private RecordingVectorRepository vectorRepository;

    @BeforeEach
    void setUp() {
        repository = new FakeCdqKnowledgeVersionRepository();
        vectorRepository = new RecordingVectorRepository();
    }

    @Test
    void unchangedScanRecordsSuccessWithoutCreatingActions() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(approvedHtml);

        CdqKnowledgeView view = workflow.scan();

        assertThat(view.lastScan().outcome()).isEqualTo(UNCHANGED);
        assertThat(view.lastScan().remoteHash()).isEqualTo(approved.snapshotHash());
        assertThat(view.candidate()).isNull();
        assertThat(view.diff()).isNull();
        assertThat(view.canReject()).isFalse();
        assertThat(view.canApprove()).isFalse();
        assertThat(view.canIngest()).isFalse();
    }

    @Test
    void changedScanCreatesAPendingCandidateAndLineDiff() {
        repository.seedActive(activeVersion(approved));

        CdqKnowledgeView view = workflowReturning(changedHtml).scan();

        assertThat(view.sourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(view.lastScan().outcome()).isEqualTo(CHANGES_DETECTED);
        assertThat(view.lastScan().candidateVersionId()).isEqualTo(view.candidate().id());
        assertThat(view.candidate().status()).isEqualTo(PENDING_REVIEW);
        assertThat(view.candidate().content()).isEqualTo(changed.text());
        assertThat(view.diff().addedLines()).isPositive();
        assertThat(view.diff().removedLines()).isPositive();
        assertThat(view.canReject()).isTrue();
        assertThat(view.canApprove()).isTrue();
        assertThat(view.canIngest()).isFalse();
        assertThat(view.active().snapshotHash()).isEqualTo(approved.snapshotHash());
    }

    @Test
    void repeatedChangedScanReusesTheIdenticalOpenCandidate() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID firstCandidateId = workflow.scan().candidate().id();

        CdqKnowledgeView secondView = workflow.scan();

        assertThat(secondView.candidate().id()).isEqualTo(firstCandidateId);
        assertThat(repository.versions()).hasSize(2);
        assertThat(repository.scans()).hasSize(2);
        assertThat(secondView.lastScan().candidateVersionId()).isEqualTo(firstCandidateId);
    }

    @Test
    void aNewRemoteHashSupersedesTheOldCandidateAndCreatesAReplacement() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID oldCandidateId = workflow.scan().candidate().id();
        String newerHtml = changedHtml.replace("an updated service", "a newly updated service");

        CdqKnowledgeView view = workflowReturning(newerHtml).scan();

        CdqKnowledgeVersion oldCandidate = repository.version(oldCandidateId);
        assertThat(oldCandidate.status()).isEqualTo(SUPERSEDED);
        assertThat(oldCandidate.supersededBy()).isEqualTo(view.candidate().id());
        assertThat(view.candidate().id()).isNotEqualTo(oldCandidateId);
        assertThat(view.candidate().status()).isEqualTo(PENDING_REVIEW);
        assertThat(repository.versions()).hasSize(3);
    }

    @Test
    void remoteActiveHashSupersedesAStaleOpenCandidateAndRecordsUnchanged() {
        repository.seedActive(activeVersion(approved));
        UUID staleCandidateId = workflowReturning(changedHtml).scan().candidate().id();

        CdqKnowledgeView view = workflowReturning(approvedHtml).scan();

        assertThat(repository.version(staleCandidateId).status()).isEqualTo(SUPERSEDED);
        assertThat(repository.version(staleCandidateId).supersededBy()).isNull();
        assertThat(view.lastScan().outcome()).isEqualTo(UNCHANGED);
        assertThat(view.lastScan().candidateVersionId()).isNull();
        assertThat(view.candidate()).isNull();
        assertThat(view.diff()).isNull();
    }

    @Test
    void approvalTrimsAnOptionalReviewCommentAndEnablesOnlyIngestAndReject() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID candidateId = workflow.scan().candidate().id();

        CdqKnowledgeView view = workflow.approve(candidateId, "  reviewed against the website  ");

        assertThat(view.candidate().status()).isEqualTo(APPROVED);
        assertThat(view.candidate().reviewedAt()).isEqualTo(NOW);
        assertThat(view.candidate().reviewComment()).isEqualTo("reviewed against the website");
        assertThat(view.canReject()).isTrue();
        assertThat(view.canApprove()).isFalse();
        assertThat(view.canIngest()).isTrue();
    }

    @Test
    void blankApprovalCommentIsPersistedAsAbsent() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID candidateId = workflow.scan().candidate().id();

        CdqKnowledgeView view = workflow.approve(candidateId, "   ");

        assertThat(view.candidate().reviewComment()).isNull();
    }

    @Test
    void rejectionClosesAnApprovedCandidate() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID candidateId = workflow.scan().candidate().id();
        workflow.approve(candidateId, null);

        CdqKnowledgeView view = workflow.reject(candidateId);

        assertThat(repository.version(candidateId).status()).isEqualTo(REJECTED);
        assertThat(repository.version(candidateId).reviewedAt()).isEqualTo(NOW);
        assertThat(view.candidate()).isNull();
        assertThat(view.diff()).isNull();
        assertThat(view.canReject()).isFalse();
        assertThat(view.canApprove()).isFalse();
        assertThat(view.canIngest()).isFalse();
    }

    @Test
    void approvalOfAnUnknownVersionReturnsNotFound() {
        repository.seedActive(activeVersion(approved));

        assertFailure(
                () -> workflowReturning(changedHtml).approve(UUID.randomUUID(), null),
                VERSION_NOT_FOUND);
    }

    @Test
    void rejectionOfAnUnknownVersionReturnsNotFound() {
        repository.seedActive(activeVersion(approved));

        assertFailure(
                () -> workflowReturning(changedHtml).reject(UUID.randomUUID()),
                VERSION_NOT_FOUND);
    }

    @Test
    void approvalRejectsAStaleCandidateState() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID candidateId = workflow.scan().candidate().id();
        workflow.approve(candidateId, null);

        assertFailure(() -> workflow.approve(candidateId, null), VERSION_STATE_CONFLICT);
    }

    @Test
    void rejectionRejectsAClosedCandidateState() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID candidateId = workflow.scan().candidate().id();
        workflow.reject(candidateId);

        assertFailure(() -> workflow.reject(candidateId), VERSION_STATE_CONFLICT);
    }

    @Test
    void approvedVersionCanBeIngestedAndBecomesTheOnlyActiveVersion() {
        CdqKnowledgeVersion oldActive = activeVersion(approved);
        repository.seedActive(oldActive);
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID approvedId = workflow.scan().candidate().id();
        workflow.approve(approvedId, "approved for activation");

        CdqKnowledgeView view = workflow.ingest(approvedId);

        assertThat(repository.version(oldActive.id()).status()).isEqualTo(INACTIVE);
        assertThat(repository.version(approvedId).status()).isEqualTo(ACTIVE);
        assertThat(repository.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::id)
                .isEqualTo(approvedId);
        assertThat(view.active().id()).isEqualTo(approvedId);
        assertThat(view.candidate()).isNull();
        assertThat(vectorRepository.snapshotHashes(SOURCE_ID))
                .containsExactly(changed.snapshotHash());
    }

    @Test
    void failedIngestLeavesApprovedCandidateAndOldActiveVersionUntouched() {
        CdqKnowledgeVersion oldActive = activeVersion(approved);
        repository.seedActive(oldActive);
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID approvedId = workflow.scan().candidate().id();
        workflow.approve(approvedId, null);
        vectorRepository.failReplacementWith(new IllegalStateException("provider secret"));

        assertThatThrownBy(() -> workflow.ingest(approvedId))
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .hasMessage(INGEST_UNAVAILABLE.name())
                .hasMessageNotContaining("provider secret")
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(INGEST_UNAVAILABLE);
        assertThat(repository.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::id)
                .isEqualTo(oldActive.id());
        assertThat(repository.findVersion(approvedId)).get()
                .extracting(CdqKnowledgeVersion::status)
                .isEqualTo(APPROVED);
    }

    @Test
    void ingestRejectsAPendingVersion() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID pendingId = workflow.scan().candidate().id();

        assertFailure(() -> workflow.ingest(pendingId), VERSION_STATE_CONFLICT);
        assertThat(vectorRepository.replacementCount()).isZero();
    }

    @Test
    void ingestRejectsAnUnknownVersion() {
        repository.seedActive(activeVersion(approved));

        assertFailure(() -> workflowReturning(changedHtml).ingest(UUID.randomUUID()), VERSION_NOT_FOUND);
        assertThat(vectorRepository.replacementCount()).isZero();
    }

    @Test
    void retryingAnAlreadyActiveVersionIsIdempotent() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowReturning(changedHtml);
        UUID approvedId = workflow.scan().candidate().id();
        workflow.approve(approvedId, null);
        workflow.ingest(approvedId);
        int replacementsAfterActivation = vectorRepository.replacementCount();

        CdqKnowledgeView retried = workflow.ingest(approvedId);

        assertThat(retried.active().id()).isEqualTo(approvedId);
        assertThat(vectorRepository.replacementCount()).isEqualTo(replacementsAfterActivation);
    }

    @Test
    void sourceFailureIsAuditedWithItsSafeCode() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowWithClient(() -> {
            throw new CdqKnowledgeOperationException(SOURCE_CONTENT_INVALID);
        });

        assertFailure(workflow::scan, SOURCE_CONTENT_INVALID);

        CdqKnowledgeScan failedScan = repository.findLatestScan(SOURCE_ID).orElseThrow();
        assertThat(failedScan.outcome()).isEqualTo(FAILED);
        assertThat(failedScan.failureCode()).isEqualTo(SOURCE_CONTENT_INVALID);
        assertThat(failedScan.remoteHash()).isNull();
        assertThat(failedScan.candidateVersionId()).isNull();
    }

    @Test
    void genericSourceFailureDoesNotLeakItsRawMessage() {
        repository.seedActive(activeVersion(approved));
        CdqKnowledgeWorkflow workflow = workflowWithClient(() -> {
            throw new IllegalStateException("secret upstream diagnostic");
        });

        assertThatThrownBy(workflow::scan)
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .hasMessage(SOURCE_UNAVAILABLE.name())
                .hasMessageNotContaining("secret upstream diagnostic");
        assertThat(repository.findLatestScan(SOURCE_ID).orElseThrow().failureCode())
                .isEqualTo(SOURCE_UNAVAILABLE);
    }

    @Test
    void denseRemoteContentIsRejectedBeforeACandidateCanBePersistedOrDiffed() {
        repository.seedActive(activeVersion(approved));
        String denseLines = IntStream.range(0, 1_001)
                .mapToObj(index -> "x")
                .collect(Collectors.joining("<br>"));
        String html = """
                <article>
                  <h1>CDQ Fraud Guard</h1>
                  <h2>Key Features of CDQ Fraud Guard</h2>
                  <p>%s</p>
                  <h2>Related Readings</h2>
                </article>
                """.formatted(denseLines);
        CdqKnowledgeWorkflow workflow = workflowReturning(html);

        assertFailure(workflow::scan, SOURCE_CONTENT_INVALID);

        assertThat(repository.versions()).singleElement()
                .extracting(CdqKnowledgeVersion::status)
                .isEqualTo(ACTIVE);
        assertThat(repository.findOpenCandidate(SOURCE_ID)).isEmpty();
        assertThat(repository.findLatestScan(SOURCE_ID)).get()
                .extracting(CdqKnowledgeScan::outcome, CdqKnowledgeScan::failureCode)
                .containsExactly(FAILED, SOURCE_CONTENT_INVALID);
    }

    private CdqKnowledgeWorkflow workflowReturning(String html) {
        return workflowWithClient(() -> new CdqWebsitePage(html, REMOTE_CAPTURED_AT));
    }

    private CdqKnowledgeWorkflow workflowWithClient(CdqWebsiteClient client) {
        return new CdqKnowledgeWorkflow(
                SOURCE_ID,
                SOURCE_URL,
                client,
                extractor,
                repository,
                new CdqKnowledgeDiffer(),
                TransactionOperations.withoutTransaction(),
                CLOCK,
                new CdqKnowledgeIngestor(vectorRepository),
                new CdqKnowledgeSnapshotFactory());
    }

    private CdqKnowledgeVersion activeVersion(CdqExtractedContent content) {
        return new CdqKnowledgeVersion(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                SOURCE_ID,
                SOURCE_URL,
                ACTIVE,
                content.text(),
                content.snapshotHash(),
                ACTIVE_CAPTURED_AT,
                ACTIVE_CAPTURED_AT,
                NOW.minusSeconds(3600),
                "initial approval",
                NOW.minusSeconds(1800),
                null);
    }

    private String fixture(String name) {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void assertFailure(ThrowingOperation operation, CdqKnowledgeFailureCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(code);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }

    private static final class RecordingVectorRepository implements CdqVectorRepository {

        private List<Document> documents = new ArrayList<>();
        private RuntimeException replacementFailure;
        private int replacementCount;

        @Override
        public Set<String> snapshotHashes(String sourceId) {
            Set<String> hashes = new LinkedHashSet<>();
            for (Document document : documents) {
                hashes.add((String) document.getMetadata().get("snapshotHash"));
            }
            return Set.copyOf(hashes);
        }

        @Override
        public void replaceSource(String sourceId, List<Document> replacement) {
            if (replacementFailure != null) {
                throw replacementFailure;
            }
            documents = List.copyOf(replacement);
            replacementCount++;
        }

        @Override
        public List<Document> search(String query, int topK, double similarityThreshold, String sourceId) {
            return List.of();
        }

        void failReplacementWith(RuntimeException failure) {
            replacementFailure = failure;
        }

        int replacementCount() {
            return replacementCount;
        }
    }
}
