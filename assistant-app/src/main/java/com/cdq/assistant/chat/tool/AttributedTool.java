package com.cdq.assistant.chat.tool;

import org.springframework.ai.tool.ToolCallback;

public record AttributedTool(SourceKind source, ToolCallback callback) {}
