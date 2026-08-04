package com.cdq.assistant.chat.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(@NotBlank @Size(max = 2000) String message) {

    public ChatRequest {
        if (message != null) {
            message = message.trim();
        }
    }
}
