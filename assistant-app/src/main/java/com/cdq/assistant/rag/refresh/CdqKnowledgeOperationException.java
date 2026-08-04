package com.cdq.assistant.rag.refresh;

public final class CdqKnowledgeOperationException extends RuntimeException {

    private final CdqKnowledgeFailureCode code;

    public CdqKnowledgeOperationException(CdqKnowledgeFailureCode code) {
        super(code.name());
        this.code = code;
    }

    public CdqKnowledgeFailureCode code() {
        return code;
    }
}
