package com.cdq.assistant.rag.refresh;

import java.util.List;

public record CdqKnowledgeDiff(int addedLines, int removedLines, List<CdqKnowledgeDiffLine> lines) {

    public CdqKnowledgeDiff {
        lines = List.copyOf(lines);
    }
}
