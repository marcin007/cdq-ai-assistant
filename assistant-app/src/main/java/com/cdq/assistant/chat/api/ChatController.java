package com.cdq.assistant.chat.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.cdq.assistant.chat.application.ChatUseCase;

@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(
        name = "assistant.chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class ChatController {

    private final ChatUseCase chatService;

    public ChatController(ChatUseCase chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return ChatResponse.from(chatService.chat(request.message()));
    }
}
