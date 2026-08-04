package com.cdq.assistant.chat.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserFacingAnswerPolicyTest {

    private final UserFacingAnswerPolicy policy = new UserFacingAnswerPolicy();

    @Test
    void returnsATrimmedOrdinaryAnswer() {
        assertThat(policy.release("  Berlin is Germany's capital.  "))
                .isEqualTo("Berlin is Germany's capital.");
    }

    @Test
    void releasesOnlyTheAnswerAfterTheObservedReasoningSuffix() {
        String modelText = """
                Okay, I called countries_get_by_name and found Berlin.
                </think>

                The capital city of Germany is Berlin.
                """;

        assertThat(policy.release(modelText))
                .isEqualTo("The capital city of Germany is Berlin.");
    }

    @Test
    void releasesOnlyTheSuffixAfterTheLastCompletedThinkingBlock() {
        String modelText = "<think>first</think>intermediate</think> Final answer ";

        assertThat(policy.release(modelText)).isEqualTo("Final answer");
    }

    @Test
    void rejectsAnUnclosedThinkingBlockWithoutLeakingIt() {
        String modelText = "<think>raw-model-reasoning fake-secret";

        assertThatThrownBy(() -> policy.release(modelText))
                .isInstanceOf(ChatDependencyException.class)
                .hasMessageNotContaining("raw-model-reasoning")
                .hasMessageNotContaining("fake-secret");
    }

    @Test
    void rejectsACompletedThinkingBlockWithoutAFinalAnswer() {
        assertThatThrownBy(() -> policy.release("private reasoning</think>  "))
                .isInstanceOf(ChatDependencyException.class)
                .hasMessageNotContaining("private reasoning");
    }

    @Test
    void rejectsBlankOrNullModelText() {
        assertThatThrownBy(() -> policy.release("  "))
                .isInstanceOf(ChatDependencyException.class);
        assertThatThrownBy(() -> policy.release(null))
                .isInstanceOf(ChatDependencyException.class);
    }
}
