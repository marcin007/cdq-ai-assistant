package com.cdq.assistant.rag;

@FunctionalInterface
public interface CdqKnowledgeSource {

    CdqKnowledgeSnapshot load();
}
