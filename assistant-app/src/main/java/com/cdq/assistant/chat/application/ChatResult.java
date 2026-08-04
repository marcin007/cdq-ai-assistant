package com.cdq.assistant.chat.application;

import java.util.List;

import com.cdq.assistant.chat.tool.SourceRecord;

public record ChatResult(String answer, List<SourceRecord> sources) {

    public ChatResult {
        sources = List.copyOf(sources);
    }
}
