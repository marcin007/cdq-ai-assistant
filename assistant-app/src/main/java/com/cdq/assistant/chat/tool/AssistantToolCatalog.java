package com.cdq.assistant.chat.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;

import com.cdq.assistant.weather.ValidatingWeatherToolCallback;

public final class AssistantToolCatalog {

    private static final String RAG_TOOL = "search_cdq_fraud_guard";
    private static final Map<String, SourceKind> MCP_TOOLS = Map.of(
            "countries_get_by_name", SourceKind.REST_COUNTRIES,
            "countries_get_by_capital", SourceKind.REST_COUNTRIES,
            "get_weather", SourceKind.WEATHER);

    private final List<AttributedTool> tools;

    public AssistantToolCatalog(ToolCallback ragCallback, List<? extends ToolCallback> mcpCallbacks) {
        List<ToolCallback> validatedMcpCallbacks = mcpCallbacks.stream()
                .map(AssistantToolCatalog::validateDependencyResult)
                .toList();
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(ragCallback);
        callbacks.addAll(validatedMcpCallbacks);

        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (byName.putIfAbsent(name, callback) != null) {
                throw new IllegalArgumentException("Duplicate assistant tool callback: " + name);
            }
            if (callback.getToolMetadata().returnDirect()) {
                throw new IllegalArgumentException("Assistant tools must not return directly: " + name);
            }
        }

        if (!RAG_TOOL.equals(ragCallback.getToolDefinition().name())) {
            throw new IllegalArgumentException("Missing required local RAG callback");
        }
        Set<String> actualMcpNames = validatedMcpCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
        if (!actualMcpNames.equals(MCP_TOOLS.keySet())) {
            throw new IllegalArgumentException("Unexpected MCP callback catalog: " + actualMcpNames);
        }

        this.tools = callbacks.stream()
                .map(callback -> new AttributedTool(sourceFor(callback), callback))
                .toList();
    }

    public List<AttributedTool> tools() {
        return tools;
    }

    private static SourceKind sourceFor(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        if (RAG_TOOL.equals(name)) {
            return SourceKind.CDQ_RAG;
        }
        return MCP_TOOLS.get(name);
    }

    private static ToolCallback validateDependencyResult(ToolCallback callback) {
        return "get_weather".equals(callback.getToolDefinition().name())
                ? new ValidatingWeatherToolCallback(callback)
                : callback;
    }
}
