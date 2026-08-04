package com.cdq.assistant.rag.api;

import jakarta.validation.constraints.Size;

public record ApproveCdqKnowledgeRequest(@Size(max = 500) String comment) {

    public ApproveCdqKnowledgeRequest {
        comment = comment == null ? null : comment.trim();
    }
}
