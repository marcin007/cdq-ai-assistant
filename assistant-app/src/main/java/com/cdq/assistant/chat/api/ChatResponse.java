package com.cdq.assistant.chat.api;

import java.util.List;

import com.cdq.assistant.chat.application.ChatResult;
import com.cdq.assistant.chat.tool.SourceRecord;

public record ChatResponse(String answer, List<SourceRecord> sources) {

    public ChatResponse {
        sources = List.copyOf(sources);
    }

    static ChatResponse from(ChatResult result) {
        return new ChatResponse(result.answer(), result.sources());
    }
}
