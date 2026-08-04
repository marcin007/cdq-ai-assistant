package com.cdq.assistant.rag;

import java.util.List;

import org.springframework.ai.document.Document;

public record CdqKnowledgeSnapshot(
        String sourceId,
        String sourceUrl,
        String capturedAt,
        String snapshotHash,
        String content,
        List<Document> chunks) {

    public CdqKnowledgeSnapshot {
        chunks = List.copyOf(chunks);
    }
}
