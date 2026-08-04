package com.cdq.assistant.rag.refresh;

import java.util.ArrayList;
import java.util.List;

import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.ADDED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.REMOVED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.UNCHANGED;

public final class CdqKnowledgeDiffer {

    private static final long MAX_NORMALIZED_LINES = 1_000;

    public CdqKnowledgeDiff compare(String previousContent, String currentContent) {
        if (previousContent.lines().count() > MAX_NORMALIZED_LINES
                || currentContent.lines().count() > MAX_NORMALIZED_LINES) {
            throw new CdqKnowledgeOperationException(CdqKnowledgeFailureCode.SOURCE_CONTENT_INVALID);
        }
        String[] previousLines = previousContent.split("\\R", -1);
        String[] currentLines = currentContent.split("\\R", -1);
        int[][] commonSuffixLengths = commonSuffixLengths(previousLines, currentLines);

        List<CdqKnowledgeDiffLine> lines = new ArrayList<>();
        int removedLines = 0;
        int addedLines = 0;
        int previousIndex = 0;
        int currentIndex = 0;
        while (previousIndex < previousLines.length && currentIndex < currentLines.length) {
            if (previousLines[previousIndex].equals(currentLines[currentIndex])) {
                lines.add(new CdqKnowledgeDiffLine(UNCHANGED, previousLines[previousIndex]));
                previousIndex++;
                currentIndex++;
            }
            else if (commonSuffixLengths[previousIndex + 1][currentIndex]
                    >= commonSuffixLengths[previousIndex][currentIndex + 1]) {
                lines.add(new CdqKnowledgeDiffLine(REMOVED, previousLines[previousIndex++]));
                removedLines++;
            }
            else {
                lines.add(new CdqKnowledgeDiffLine(ADDED, currentLines[currentIndex++]));
                addedLines++;
            }
        }
        while (previousIndex < previousLines.length) {
            lines.add(new CdqKnowledgeDiffLine(REMOVED, previousLines[previousIndex++]));
            removedLines++;
        }
        while (currentIndex < currentLines.length) {
            lines.add(new CdqKnowledgeDiffLine(ADDED, currentLines[currentIndex++]));
            addedLines++;
        }
        return new CdqKnowledgeDiff(addedLines, removedLines, lines);
    }

    private int[][] commonSuffixLengths(String[] previousLines, String[] currentLines) {
        int[][] lengths = new int[previousLines.length + 1][currentLines.length + 1];
        for (int previousIndex = previousLines.length - 1; previousIndex >= 0; previousIndex--) {
            for (int currentIndex = currentLines.length - 1; currentIndex >= 0; currentIndex--) {
                if (previousLines[previousIndex].equals(currentLines[currentIndex])) {
                    lengths[previousIndex][currentIndex] = lengths[previousIndex + 1][currentIndex + 1] + 1;
                }
                else {
                    lengths[previousIndex][currentIndex] = Math.max(
                            lengths[previousIndex + 1][currentIndex],
                            lengths[previousIndex][currentIndex + 1]);
                }
            }
        }
        return lengths;
    }
}
