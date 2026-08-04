package com.cdq.assistant.rag.refresh;

import org.junit.jupiter.api.Test;

import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.ADDED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.REMOVED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine.Type.UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdqKnowledgeDifferTest {

    @Test
    void marksAddedRemovedAndUnchangedLinesWithoutExecutingHtml() {
        CdqKnowledgeDiff diff = new CdqKnowledgeDiffer().compare(
                "Heading\n\nOld <script>alert(1)</script>\n",
                "Heading\n\nNew <img src=x>\n");

        assertThat(diff.addedLines()).isEqualTo(1);
        assertThat(diff.removedLines()).isEqualTo(1);
        assertThat(diff.lines()).contains(
                new CdqKnowledgeDiffLine(UNCHANGED, "Heading"),
                new CdqKnowledgeDiffLine(REMOVED, "Old <script>alert(1)</script>"),
                new CdqKnowledgeDiffLine(ADDED, "New <img src=x>"));
    }

    @Test
    void producesTheSameLiteralOrderingForRepeatedLines() {
        CdqKnowledgeDiff first = new CdqKnowledgeDiffer().compare("A\nB\nA\n", "A\nA\nB\n");
        CdqKnowledgeDiff second = new CdqKnowledgeDiffer().compare("A\nB\nA\n", "A\nA\nB\n");

        assertThat(first).isEqualTo(second);
        assertThat(first.lines()).containsExactly(
                new CdqKnowledgeDiffLine(UNCHANGED, "A"),
                new CdqKnowledgeDiffLine(REMOVED, "B"),
                new CdqKnowledgeDiffLine(UNCHANGED, "A"),
                new CdqKnowledgeDiffLine(ADDED, "B"),
                new CdqKnowledgeDiffLine(UNCHANGED, ""));
    }

    @Test
    void rejectsDenseContentBeforeAllocatingAnUnboundedLineMatrix() {
        String densePrevious = "previous\n".repeat(1_001);
        String denseCurrent = "current\n".repeat(1_001);

        assertThatThrownBy(() -> new CdqKnowledgeDiffer().compare(densePrevious, denseCurrent))
                .isInstanceOf(CdqKnowledgeOperationException.class)
                .extracting(error -> ((CdqKnowledgeOperationException) error).code())
                .isEqualTo(CdqKnowledgeFailureCode.SOURCE_CONTENT_INVALID);
    }
}
