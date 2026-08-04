package com.cdq.assistant.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdqDocumentIdsTest {

    private static final String SNAPSHOT_HASH =
            "35fe98e4df21b5811132758f3aa805b704b8ba948d9fe6384d30cfaf0b6f30cc";

    @Test
    void derivesStableUuidFromSnapshotHashAndChunkIndex() {
        assertThat(CdqDocumentIds.forSnapshotChunk(SNAPSHOT_HASH, 0))
                .isEqualTo("2bd999e5-def5-3582-a31e-2a041056fae1");
        assertThat(CdqDocumentIds.forSnapshotChunk(SNAPSHOT_HASH, 1))
                .isEqualTo("5f55c98b-edc7-3841-bfd9-2e67793faf74");
        assertThat(CdqDocumentIds.forSnapshotChunk(SNAPSHOT_HASH, 0))
                .isEqualTo(CdqDocumentIds.forSnapshotChunk(SNAPSHOT_HASH, 0));
    }
}
