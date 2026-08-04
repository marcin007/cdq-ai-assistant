package com.cdq.assistant.weather;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ValidatingWeatherToolCallback implements ToolCallback {

    private static final String FAILURE_MESSAGE = "Weather service did not return a valid temperature.";
    private static final Pattern TEMPERATURE =
            Pattern.compile("\\bcurrently:\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*$", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;

    public ValidatingWeatherToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
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
        String output;
        try {
            output = delegate.call(input);
        }
        catch (RuntimeException exception) {
            throw failure();
        }
        return validate(output);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        String output;
        try {
            output = delegate.call(input, toolContext);
        }
        catch (RuntimeException exception) {
            throw failure();
        }
        return validate(output);
    }

    private String validate(String output) {
        if (!hasNumericTemperature(output)) {
            throw failure();
        }
        return output;
    }

    private boolean hasNumericTemperature(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        try {
            JsonNode content = JSON.readTree(output);
            if (content == null || !content.isArray() || content.size() != 1) {
                return false;
            }
            JsonNode item = content.get(0);
            JsonNode type = item == null ? null : item.get("type");
            if (item == null
                    || (type != null && !"text".equals(type.stringValue()))
                    || !item.path("text").isString()) {
                return false;
            }
            Matcher temperature = TEMPERATURE.matcher(item.path("text").stringValue());
            return temperature.find() && Double.isFinite(Double.parseDouble(temperature.group(1)));
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    private ToolExecutionException failure() {
        return new ToolExecutionException(
                getToolDefinition(), new IllegalStateException(FAILURE_MESSAGE));
    }
}
