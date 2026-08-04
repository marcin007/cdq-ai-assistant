package com.cdq.assistant.chat.tool;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ToolInvocationLedger {

    private final Set<SourceKind> successfulSources = new LinkedHashSet<>();

    public void record(SourceKind source) {
        successfulSources.add(source);
    }

    public List<SourceRecord> toSourceRecords() {
        return successfulSources.stream().map(SourceRecord::from).toList();
    }
}
