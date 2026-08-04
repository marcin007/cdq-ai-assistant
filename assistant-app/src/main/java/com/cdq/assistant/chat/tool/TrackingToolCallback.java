package com.cdq.assistant.chat.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public final class TrackingToolCallback implements ToolCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackingToolCallback.class);

    private final ToolCallback delegate;
    private final SourceKind source;
    private final ToolInvocationLedger ledger;

    public TrackingToolCallback(
            ToolCallback delegate, SourceKind source, ToolInvocationLedger ledger) {
        this.delegate = delegate;
        this.source = source;
        this.ledger = ledger;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        try {
            String result = delegate.call(input);
            ledger.record(source);
            return result;
        }
        catch (RuntimeException exception) {
            logFailure();
            throw exception;
        }
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        try {
            String result = delegate.call(input, toolContext);
            ledger.record(source);
            return result;
        }
        catch (RuntimeException exception) {
            logFailure();
            throw exception;
        }
    }

    private void logFailure() {
        LOGGER.warn(
                "Assistant tool invocation failed; source={}; dependency details were suppressed.",
                source.name());
    }
}
