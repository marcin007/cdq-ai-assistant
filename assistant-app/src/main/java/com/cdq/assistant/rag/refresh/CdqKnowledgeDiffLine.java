package com.cdq.assistant.rag.refresh;

public record CdqKnowledgeDiffLine(Type type, String text) {

    public enum Type {
        ADDED, REMOVED, UNCHANGED
    }
}
