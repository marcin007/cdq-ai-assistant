package com.cdq.assistant.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assistant.chat")
public record ChatProperties(String model, int maxOutputTokens, Duration timeout) {

    public ChatProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("assistant.chat.model must not be blank");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "assistant.chat.max-output-tokens must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("assistant.chat.timeout must be positive");
        }
    }
}
