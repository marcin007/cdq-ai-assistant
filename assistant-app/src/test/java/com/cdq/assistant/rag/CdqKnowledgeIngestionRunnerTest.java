package com.cdq.assistant.rag;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionOperations;

import com.cdq.assistant.rag.refresh.CdqKnowledgeDiffer;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersion;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus;
import com.cdq.assistant.rag.refresh.CdqKnowledgeWorkflow;
import com.cdq.assistant.rag.refresh.CdqWebsiteContentExtractor;
import com.cdq.assistant.rag.refresh.CdqWebsitePage;
import com.cdq.assistant.rag.refresh.FakeCdqKnowledgeVersionRepository;

import static org.assertj.core.api.Assertions.assertThat;

class CdqKnowledgeIngestionRunnerTest {

    private static final String SOURCE_ID = "cdq-fraud-guard";
    private static final URI SOURCE_URL = URI.create("https://www.cdq.com/products/cdq-fraud-guard");
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DATABASE_CONTENT = "Database-approved fraud prevention content ".repeat(8);
    private static final String BUNDLED_CONTENT = "Bundled fraud prevention content ".repeat(8);

    @Test
    void databaseActiveVersionWinsOverADifferentBundledSnapshotOnRestart() throws Exception {
        FakeCdqKnowledgeVersionRepository versions = new FakeCdqKnowledgeVersionRepository();
        CdqKnowledgeSnapshot databaseSnapshot = snapshot(DATABASE_CONTENT, "2026-08-04T08:00:00Z");
        versions.seedActive(activeVersion(databaseSnapshot));
        AtomicInteger bundledLoads = new AtomicInteger();
        CdqKnowledgeSnapshot bundledSnapshot = snapshot(BUNDLED_CONTENT, "2026-07-26T08:22:11Z");
        FakeCdqVectorRepository vectors = new FakeCdqVectorRepository();
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(vectors);
        CdqKnowledgeWorkflow workflow = workflow(versions, ingestor);
        CdqKnowledgeIngestionRunner runner = new CdqKnowledgeIngestionRunner(
                workflow,
                () -> {
                    bundledLoads.incrementAndGet();
                    return bundledSnapshot;
                });

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(bundledLoads).hasValue(0);
        assertThat(vectors.snapshotHashes(SOURCE_ID)).containsExactly(databaseSnapshot.snapshotHash());
        assertThat(vectors.documents(SOURCE_ID))
                .extracting(document -> document.getMetadata().get("snapshotHash"))
                .containsOnly(databaseSnapshot.snapshotHash());
        assertThat(versions.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::snapshotHash)
                .isEqualTo(databaseSnapshot.snapshotHash());
    }

    @Test
    void emptyDatabaseBootstrapsAndIngestsTheBundledSnapshot() throws Exception {
        FakeCdqKnowledgeVersionRepository versions = new FakeCdqKnowledgeVersionRepository();
        CdqKnowledgeSnapshot bundledSnapshot = snapshot(BUNDLED_CONTENT, "2026-07-26T08:22:11Z");
        FakeCdqVectorRepository vectors = new FakeCdqVectorRepository();
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(vectors);
        CdqKnowledgeIngestionRunner runner = new CdqKnowledgeIngestionRunner(
                workflow(versions, ingestor), () -> bundledSnapshot);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(versions.findActive(SOURCE_ID)).get()
                .extracting(CdqKnowledgeVersion::snapshotHash)
                .isEqualTo(bundledSnapshot.snapshotHash());
        assertThat(vectors.snapshotHashes(SOURCE_ID)).containsExactly(bundledSnapshot.snapshotHash());
    }

    private CdqKnowledgeWorkflow workflow(
            FakeCdqKnowledgeVersionRepository versions, CdqKnowledgeIngestor ingestor) {
        return new CdqKnowledgeWorkflow(
                SOURCE_ID,
                SOURCE_URL,
                () -> new CdqWebsitePage("<html></html>", NOW),
                new CdqWebsiteContentExtractor(),
                versions,
                new CdqKnowledgeDiffer(),
                TransactionOperations.withoutTransaction(),
                CLOCK,
                ingestor,
                new CdqKnowledgeSnapshotFactory());
    }

    private CdqKnowledgeVersion activeVersion(CdqKnowledgeSnapshot snapshot) {
        Instant capturedAt = Instant.parse(snapshot.capturedAt());
        return new CdqKnowledgeVersion(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                SOURCE_ID,
                SOURCE_URL,
                CdqKnowledgeVersionStatus.ACTIVE,
                snapshot.content(),
                snapshot.snapshotHash(),
                capturedAt,
                capturedAt,
                null,
                null,
                capturedAt,
                null);
    }

    private CdqKnowledgeSnapshot snapshot(String content, String capturedAt) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new CdqKnowledgeSnapshotFactory().create(
                SOURCE_URL.toString(), capturedAt, sha256(bytes), bytes);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
