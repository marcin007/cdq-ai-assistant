package com.cdq.assistant.weather;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

import com.cdq.assistant.AssistantApplication;
import com.cdq.assistant.chat.tool.AssistantToolCatalog;
import com.cdq.assistant.chat.tool.SourceKind;
import com.cdq.assistant.chat.tool.ToolInvocationLedger;
import com.cdq.assistant.chat.tool.TrackingToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class WeatherMcpOfflineContractTest {

    private static final String FAKE_WEATHER_API_KEY = "fake-weather-api-key-in-provider-error";

    @Test
    void discoversInvokesAndKeepsWeatherDependencyFailuresOutOfApplicationLogs(
            @TempDir Path temporaryDirectory, CapturedOutput output) throws Exception {
        Path processLog = temporaryDirectory.resolve("weather-mcp-contract.log");
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("spring.main.web-application-type", "none"),
                Map.entry("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                        "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                        "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
                        "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration",
                        "org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration")),
                Map.entry("assistant.chat.enabled", "false"),
                Map.entry("cdq.rag.enabled", "false"),
                Map.entry("spring.flyway.enabled", "false"),
                Map.entry("WEATHER_API_KEY", FAKE_WEATHER_API_KEY),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.command", javaCommand()),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.args[0]", "-cp"),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.args[1]", testClasspath()),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.args[2]", "com.cdq.assistant.weather.FakeWeatherMcpServer"),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.env.WEATHER_API_URL", "https://api.weatherapi.com/v1/current.json"),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.env.WEATHER_API_KEY", FAKE_WEATHER_API_KEY),
                Map.entry("spring.ai.mcp.client.stdio.connections.weather.env.WEATHER_MCP_PROCESS_LOG", processLog.toString()));

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AssistantApplication.class)
                .run(commandLineArguments(properties))) {
            SyncMcpToolCallbackProvider provider = context.getBean(SyncMcpToolCallbackProvider.class);
            List<SyncMcpToolCallback> weatherCallbacks = java.util.Arrays.stream(provider.getToolCallbacks())
                    .filter(SyncMcpToolCallback.class::isInstance)
                    .map(SyncMcpToolCallback.class::cast)
                    .filter(callback -> callback.getOriginalToolName().equals("get-weather"))
                    .toList();

            assertThat(weatherCallbacks).singleElement().satisfies(callback -> {
                assertThat(callback.getToolDefinition().name()).isEqualTo("get_weather");
                assertThat(callback.getToolDefinition().inputSchema())
                        .contains("\"city\"")
                        .contains("\"required\"")
                        .contains("\"type\":\"string\"");
                String validOutput = callback.call("{\"city\":\"Warsaw\"}");
                assertThat(validOutput).contains("17.5");
                assertWeatherAttributionThroughRealToolManager(callback);
                assertThat(callback.call("{\"city\":\"Failure\"}")).contains("Some error occured.");
                assertWeatherFailureThroughRealToolManager(callback, "Failure");
                assertWeatherFailureThroughRealToolManager(callback, "Paris");
            });
        }

        String safeWeatherWarning =
                "Assistant tool invocation failed; source=WEATHER; dependency details were suppressed.";
        assertThat(output)
                .contains(safeWeatherWarning)
                .doesNotContain(FAKE_WEATHER_API_KEY)
                .doesNotContain("provider rejected key=")
                .doesNotContain("Some error occured.")
                .doesNotContain("Weather MCP invocation failed");
        assertThat(output.getAll().lines().filter(line -> line.contains(safeWeatherWarning)))
                .hasSize(2);
        String processEvents = awaitStopped(processLog);
        assertThat(processEvents)
                .contains("url=https://api.weatherapi.com/v1/current.json")
                .contains("key-present=true")
                .contains("initialized-notification")
                .contains("list-tools")
                .contains("call:Warsaw")
                .contains("call:Failure")
                .contains("stopped");
    }

    private static void assertWeatherAttributionThroughRealToolManager(
            ToolCallback weatherCallback) {
        ToolCallback rag = callback("search_cdq_fraud_guard");
        List<ToolCallback> mcpCallbacks = List.of(
                callback("countries_get_by_name"),
                callback("countries_get_by_capital"),
                weatherCallback);
        AssistantToolCatalog catalog = new AssistantToolCatalog(rag, mcpCallbacks);
        ToolInvocationLedger ledger = new ToolInvocationLedger();
        List<ToolCallback> trackedCallbacks = catalog.tools().stream()
                .map(tool -> new TrackingToolCallback(tool.callback(), tool.source(), ledger))
                .map(ToolCallback.class::cast)
                .toList();
        OllamaChatOptions options =
                OllamaChatOptions.builder().toolCallbacks(trackedCallbacks).build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("test"), new UserMessage("weather")), options);
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall(
                        "weather-call", "function", "get_weather", "{\"city\":\"Warsaw\"}");
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));

        DefaultToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build()
                .executeToolCalls(prompt, response);

        assertThat(ledger.toSourceRecords())
                .extracting(source -> source.kind())
                .containsExactly(SourceKind.WEATHER);
    }

    private static void assertWeatherFailureThroughRealToolManager(
            ToolCallback weatherCallback, String city) {
        ToolCallback rag = callback("search_cdq_fraud_guard");
        AssistantToolCatalog catalog = new AssistantToolCatalog(
                rag,
                List.of(
                        callback("countries_get_by_name"),
                        callback("countries_get_by_capital"),
                        weatherCallback));
        ToolInvocationLedger ledger = new ToolInvocationLedger();
        List<ToolCallback> trackedCallbacks = catalog.tools().stream()
                .map(tool -> new TrackingToolCallback(tool.callback(), tool.source(), ledger))
                .map(ToolCallback.class::cast)
                .toList();
        OllamaChatOptions options =
                OllamaChatOptions.builder().toolCallbacks(trackedCallbacks).build();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage("test"), new UserMessage("weather")), options);
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall(
                        "weather-failure", "function", "get_weather", "{\"city\":\"" + city + "\"}");
        ChatResponse response = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));

        assertThatThrownBy(() -> DefaultToolCallingManager.builder()
                        .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                        .build()
                        .executeToolCalls(prompt, response))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Weather service did not return a valid temperature.")
                .hasMessageNotContaining(FAKE_WEATHER_API_KEY);
        assertThat(ledger.toSourceRecords()).isEmpty();
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return "unused";
            }
        };
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String testClasspath() {
        return System.getProperty("surefire.test.class.path");
    }

    private static String[] commandLineArguments(Map<String, Object> properties) {
        return properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static String awaitStopped(Path processLog) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            String events = Files.readString(processLog);
            if (events.contains("stopped")) {
                return events;
            }
            Thread.sleep(100);
        }
        return Files.readString(processLog);
    }
}
