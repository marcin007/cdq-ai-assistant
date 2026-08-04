package com.cdq.assistant.rag.refresh;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record CdqKnowledgeVersion(
        UUID id,
        String sourceId,
        URI sourceUrl,
        CdqKnowledgeVersionStatus status,
        String content,
        String snapshotHash,
        Instant capturedAt,
        Instant createdAt,
        Instant reviewedAt,
        String reviewComment,
        Instant activatedAt,
        UUID supersededBy) {
}
