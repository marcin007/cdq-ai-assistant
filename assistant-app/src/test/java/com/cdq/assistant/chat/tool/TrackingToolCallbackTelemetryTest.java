package com.cdq.assistant.chat.tool;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class TrackingToolCallbackTelemetryTest {

    private static final String FAKE_KEY = "fake-tool-api-key";
    private static final String RAW_BODY = "raw-provider-body";
    private static final String SECRET_URL = "https://provider.example.test/data?key=" + FAKE_KEY;

    @ParameterizedTest
    @EnumSource(SourceKind.class)
    void logsOnlyTheSafeSourceCategoryWhenAToolFails(
            SourceKind source, CapturedOutput output) {
        ToolCallback callback = new TrackingToolCallback(
                failingCallback(), source, new ToolInvocationLedger());

        assertThatThrownBy(() -> callback.call(
                        "{\"url\":\"" + SECRET_URL + "\",\"body\":\"" + RAW_BODY + "\"}"))
                .isInstanceOf(IllegalStateException.class);

        String safeWarning =
                "Assistant tool invocation failed; source=" + source
                        + "; dependency details were suppressed.";
        assertThat(output)
                .contains(safeWarning)
                .doesNotContain(FAKE_KEY)
                .doesNotContain(RAW_BODY)
                .doesNotContain(SECRET_URL)
                .doesNotContain("IllegalStateException");
        assertThat(output.getAll().lines().filter(line -> line.contains(safeWarning)))
                .hasSize(1);
    }

    private static ToolCallback failingCallback() {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("failing_tool")
                    .description("Always fails")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException(
                        RAW_BODY + " " + SECRET_URL + " " + FAKE_KEY);
            }
        };
    }
}
