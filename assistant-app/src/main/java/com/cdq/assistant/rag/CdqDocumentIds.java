package com.cdq.assistant.rag;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class CdqDocumentIds {

    private CdqDocumentIds() {
    }

    /**
     * Uses Java's name-based UUID algorithm over the UTF-8 bytes of
     * {@code snapshotHash + ":" + chunkIndex}.
     */
    public static String forSnapshotChunk(String snapshotHash, int chunkIndex) {
        String name = snapshotHash + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
