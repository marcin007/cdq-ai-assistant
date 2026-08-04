package com.cdq.assistant.chat.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatPropertiesTest {

    @Test
    void acceptsThePinnedModelPositiveOutputLimitAndDeadline() {
        assertThatCode(() -> new ChatProperties(
                        "qwen3:4b-instruct-2507-q4_K_M", 256, Duration.ofSeconds(45)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankModelNonPositiveOutputLimitAndNonPositiveDeadline() {
        assertThatThrownBy(() -> new ChatProperties(" ", 256, Duration.ofSeconds(45)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatProperties("model", 0, Duration.ofSeconds(45)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatProperties("model", 256, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
