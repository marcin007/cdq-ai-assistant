package com.cdq.assistant.chat.tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantToolCatalogWeatherValidationTest {

    private final RecordingCallback weather = new RecordingCallback("get_weather");
    private final AssistantToolCatalog catalog = new AssistantToolCatalog(
            new RecordingCallback("search_cdq_fraud_guard"),
            List.of(
                    new RecordingCallback("countries_get_by_name"),
                    new RecordingCallback("countries_get_by_capital"),
                    weather));

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"type\":\"text\",\"text\":\"the weather in Warsaw is currently: 17.5\"}]",
            "[{\"type\":\"text\",\"text\":\"the weather in Tromso is currently: -12.25\"}]"
    })
    void returnsCanonicalNumericWeatherContentUnchanged(String output) {
        weather.output = output;

        assertThat(weatherCallback().call("{\"city\":\"Warsaw\"}")).isEqualTo(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"type\":\"text\",\"text\":\"Some error occured. raw-secret-body test-api-key\"}]",
            "[{\"type\":\"text\",\"text\":\"the weather in Warsaw is currently: undefined\"}]",
            "[{\"type\":\"text\",\"text\":\"the weather in Warsaw is currently: warm\"}]",
            "[{\"type\":\"text\",\"text\":\"\"}]",
            "[]",
            "{not-json}",
            " "
    })
    void rejectsInvalidWeatherContentWithoutRecordingOrLeakingIt(String output) {
        weather.output = output;
        ToolInvocationLedger ledger = new ToolInvocationLedger();
        ToolCallback tracked = new TrackingToolCallback(weatherCallback(), SourceKind.WEATHER, ledger);

        assertThatThrownBy(() -> tracked.call("{\"city\":\"Warsaw\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Weather service did not return a valid temperature.")
                .hasMessageNotContaining("raw-secret-body")
                .hasMessageNotContaining("test-api-key")
                .hasMessageNotContaining("undefined");
        assertThat(ledger.toSourceRecords()).isEmpty();
    }

    @Test
    void preservesDefinitionMetadataAndTheContextAwareDelegateCall() {
        String output = "[{\"type\":\"text\",\"text\":\"the weather in Warsaw is currently: 0\"}]";
        weather.output = output;
        ToolCallback callback = weatherCallback();
        ToolContext context = new ToolContext(Map.of("request-id", "weather-test"));

        assertThat(callback.getToolDefinition()).isSameAs(weather.definition);
        assertThat(callback.getToolMetadata()).isSameAs(weather.metadata);
        assertThat(callback.call("{\"city\":\"Warsaw\"}", context)).isEqualTo(output);
        assertThat(weather.lastContext).isSameAs(context);
    }

    @Test
    void sanitizesAWeatherDelegateExceptionWithoutRecordingIt() {
        weather.failure = new IllegalStateException("raw-secret-body test-api-key");
        ToolInvocationLedger ledger = new ToolInvocationLedger();
        ToolCallback tracked = new TrackingToolCallback(weatherCallback(), SourceKind.WEATHER, ledger);

        assertThatThrownBy(() -> tracked.call("{\"city\":\"Warsaw\"}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Weather service did not return a valid temperature.")
                .hasMessageNotContaining("raw-secret-body")
                .hasMessageNotContaining("test-api-key");
        assertThat(ledger.toSourceRecords()).isEmpty();
    }

    private ToolCallback weatherCallback() {
        return catalog.tools().stream()
                .map(AttributedTool::callback)
                .filter(callback -> callback.getToolDefinition().name().equals("get_weather"))
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final ToolMetadata metadata = ToolMetadata.builder().returnDirect(false).build();
        private String output = "unused";
        private ToolContext lastContext;
        private RuntimeException failure;

        private RecordingCallback(String name) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata;
        }

        @Override
        public String call(String input) {
            if (failure != null) {
                throw failure;
            }
            return output;
        }

        @Override
        public String call(String input, ToolContext toolContext) {
            lastContext = toolContext;
            return output;
        }
    }
}
