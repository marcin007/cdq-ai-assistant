package com.cdq.assistant.rag.refresh;

import java.net.URI;

public record CdqKnowledgeView(
        URI sourceUrl,
        CdqKnowledgeVersion active,
        CdqKnowledgeScan lastScan,
        CdqKnowledgeVersion candidate,
        CdqKnowledgeDiff diff,
        boolean canReject,
        boolean canApprove,
        boolean canIngest) {
}
