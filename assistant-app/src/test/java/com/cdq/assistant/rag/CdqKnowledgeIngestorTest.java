package com.cdq.assistant.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class CdqKnowledgeIngestorTest {

    private static final String CURRENT_HASH =
            "35fe98e4df21b5811132758f3aa805b704b8ba948d9fe6384d30cfaf0b6f30cc";

    @Test
    void skipsAllWritesWhenEveryStoredRowHasTheCurrentHash() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        Document existing = document("existing-id", "Existing chunk", "cdq-fraud-guard", CURRENT_HASH, 0);
        Document unrelated = document("other-id", "Other source", "other-source", "other-hash", 0);
        repository.seed("cdq-fraud-guard", existing);
        repository.seed("other-source", unrelated);
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);

        CdqIngestionOutcome outcome = ingestor.ingest(snapshot(CURRENT_HASH));

        assertThat(outcome).isEqualTo(CdqIngestionOutcome.SKIPPED);
        assertThat(repository.replacementCount()).isZero();
        assertThat(repository.documents("cdq-fraud-guard")).containsExactly(existing);
        assertThat(repository.documents("other-source")).containsExactly(unrelated);
    }

    @Test
    void replacesOnlyCdqRowsWhenTheStoredHashChanged() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        repository.seed(
                "cdq-fraud-guard",
                document("old-id", "Old CDQ chunk", "cdq-fraud-guard", "old-hash", 0));
        Document unrelated = document("other-id", "Other source", "other-source", "other-hash", 0);
        repository.seed("other-source", unrelated);
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);

        CdqIngestionOutcome outcome = ingestor.ingest(snapshot(CURRENT_HASH));

        assertThat(outcome).isEqualTo(CdqIngestionOutcome.REPLACED);
        assertThat(repository.documents("cdq-fraud-guard"))
                .extracting(Document::getId)
                .containsExactly(
                        "2bd999e5-def5-3582-a31e-2a041056fae1",
                        "5f55c98b-edc7-3841-bfd9-2e67793faf74");
        assertThat(repository.documents("cdq-fraud-guard"))
                .extracting(document -> document.getMetadata().get("snapshotHash"))
                .containsOnly(CURRENT_HASH);
        assertThat(repository.documents("other-source")).containsExactly(unrelated);
    }

    @Test
    void ingestsTheSnapshotWhenNoCdqRowsExist() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);

        CdqIngestionOutcome outcome = ingestor.ingest(snapshot(CURRENT_HASH));

        assertThat(outcome).isEqualTo(CdqIngestionOutcome.REPLACED);
        assertThat(repository.documents("cdq-fraud-guard")).hasSize(2);
    }

    @Test
    void replacesRowsWhenAnyStoredCdqRowHasNoSnapshotHash() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        repository.seed(
                "cdq-fraud-guard",
                document("current-id", "Current", "cdq-fraud-guard", CURRENT_HASH, 0),
                new Document(
                        "missing-hash-id",
                        "Missing provenance",
                        Map.of(
                                "sourceId", "cdq-fraud-guard",
                                "sourceUrl", "https://example.test/cdq-fraud-guard",
                                "capturedAt", "2026-07-26T08:22:11Z",
                                "chunkIndex", 1)));
        CdqKnowledgeIngestor ingestor = new CdqKnowledgeIngestor(repository);

        CdqIngestionOutcome outcome = ingestor.ingest(snapshot(CURRENT_HASH));

        assertThat(outcome).isEqualTo(CdqIngestionOutcome.REPLACED);
        assertThat(repository.documents("cdq-fraud-guard"))
                .extracting(document -> document.getMetadata().get("snapshotHash"))
                .containsOnly(CURRENT_HASH);
    }

    private static CdqKnowledgeSnapshot snapshot(String snapshotHash) {
        List<Document> chunks = List.of(
                document("temporary-0", "First current chunk", "cdq-fraud-guard", snapshotHash, 0),
                document("temporary-1", "Second current chunk", "cdq-fraud-guard", snapshotHash, 1));
        return new CdqKnowledgeSnapshot(
                "cdq-fraud-guard",
                "https://www.cdq.com/products/cdq-fraud-guard",
                "2026-07-26T08:22:11Z",
                snapshotHash,
                "First current chunk\nSecond current chunk",
                chunks);
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
}
