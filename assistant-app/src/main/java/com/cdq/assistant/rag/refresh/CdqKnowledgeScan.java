package com.cdq.assistant.rag.refresh;

import java.time.Instant;
import java.util.UUID;

public record CdqKnowledgeScan(
        UUID id,
        String sourceId,
        Instant scannedAt,
        CdqKnowledgeScanOutcome outcome,
        String remoteHash,
        UUID candidateVersionId,
        CdqKnowledgeFailureCode failureCode) {
}
