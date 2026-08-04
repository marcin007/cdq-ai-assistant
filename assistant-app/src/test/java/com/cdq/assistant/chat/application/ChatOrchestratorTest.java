package com.cdq.assistant.chat.application;

import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;

import com.cdq.assistant.chat.tool.AssistantToolCatalog;
import com.cdq.assistant.chat.tool.SourceKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ChatOrchestratorTest {

    private static final String EXPECTED_SYSTEM_POLICY =
            "Answer in the same language as the user's current message. "
                    + "Use countries_get_by_name or countries_get_by_capital for country and capital facts. "
                    + "Use get_weather for current temperature. "
                    + "Use search_cdq_fraud_guard for CDQ Fraud Guard product facts. "
                    + "Never invent data that a required tool does not provide. "
                    + "Weather temperature values are Celsius because the weather server returns current.temp_c. "
                    + "For multi-step factual answers, state each factual relationship explicitly and associate each dynamic value with the entity it describes. "
                    + "For a country-capital weather request, explicitly state that the capital is the capital of the country and explicitly state the current temperature in that capital.";

    private final RecordingTool rag = new RecordingTool("search_cdq_fraud_guard", "CDQ facts");
    private final RecordingTool countryByName = new RecordingTool("countries_get_by_name", "Poland facts");
    private final RecordingTool countryByCapital =
            new RecordingTool("countries_get_by_capital", "Capital facts");
    private final RecordingTool weather = new RecordingTool(
            "get_weather",
            "[{\"type\":\"text\",\"text\":\"the weather in Warsaw is currently: 17.5\"}]");

    @Test
    void returnsADirectAnswerWithoutSourcesAndUsesTheRequiredFreshPromptOptions() {
        RecordingChatModel model = new RecordingChatModel(finalResponse("Hello"));

        ChatResult result = orchestrator(model).chat("Hi");

        assertThat(result.answer()).isEqualTo("Hello");
        assertThat(result.sources()).isEmpty();
        assertThat(model.prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt.getInstructions())
                    .extracting(Message::getMessageType)
                    .containsExactly(MessageType.SYSTEM, MessageType.USER);
            assertThat(prompt.getInstructions())
                    .extracting(Message::getText)
                    .containsExactly(EXPECTED_SYSTEM_POLICY, "Hi");
            assertRequiredOptions(prompt);
        });
    }

    @Test
    void rejectsAFactualDirectAnswerWithoutEvidence() {
        RecordingChatModel model = new RecordingChatModel(finalResponse("Berlin"));

        assertThatThrownBy(() -> orchestrator(model).chat("What is Germany's capital?"))
                .isInstanceOf(ChatGroundingException.class);
    }

    @Test
    void rejectsAMultiSourceAnswerWhenWeatherDidNotRun() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"), finalResponse("Berlin, 20 C"));

        assertThatThrownBy(() -> orchestrator(model)
                .chat("What is the temperature of Germany's capital?"))
                .isInstanceOf(ChatGroundingException.class);
    }

    @Test
    void attributesOnlyTheSuccessfullyExecutedCountryCallback() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"), finalResponse("Warsaw"));

        ChatResult result = orchestrator(model).chat("What is Poland's capital?");

        assertThat(result.sources())
                .extracting(source -> source.kind(), source -> source.label(), source -> source.url().toString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        SourceKind.REST_COUNTRIES,
                        "REST Countries v5",
                        "https://restcountries.com/"));
        assertThat(countryByName.calls).isEqualTo(1);
        assertThat(countryByCapital.calls).isZero();
    }

    @Test
    void removesObservedQwenReasoningBeforeReturningAToolGroundedAnswer() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"),
                finalResponse("""
                        Okay, I used the country tool and found Berlin.
                        </think>

                        The capital city of Germany is Berlin.
                        """));

        ChatResult result = orchestrator(model).chat("What is the capital city of Germany?");

        assertThat(result.answer()).isEqualTo("The capital city of Germany is Berlin.");
        assertThat(result.answer()).doesNotContain("Okay", "think");
        assertThat(result.sources())
                .extracting(source -> source.kind())
                .containsExactly(SourceKind.REST_COUNTRIES);
    }

    @Test
    void recognizesTheNormalizedWeatherCallbackName() {
        RecordingChatModel model =
                new RecordingChatModel(toolResponse("get_weather"), finalResponse("17.5 °C"));

        ChatResult result = orchestrator(model).chat("Weather in Warsaw?");

        assertThat(result.sources())
                .extracting(source -> source.kind(), source -> source.label(), source -> source.url().toString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        SourceKind.WEATHER,
                        "WeatherAPI via semdin/mcp-weather",
                        "https://github.com/semdin/mcp-weather"));
        assertThat(weather.calls).isEqualTo(1);
    }

    @Test
    void preservesFirstSuccessfulSourceUseAndDeduplicatesLaterUses() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"),
                toolResponse("countries_get_by_capital"),
                toolResponse("get_weather"),
                finalResponse("Done"));

        ChatResult result = orchestrator(model).chat("Country and weather");

        assertThat(result.sources())
                .extracting(source -> source.kind())
                .containsExactly(SourceKind.REST_COUNTRIES, SourceKind.WEATHER);
        assertThat(model.prompts).hasSize(4);
        for (int round = 0; round < model.prompts.size(); round++) {
            assertRequiredOptions(model.prompts.get(round), round + 1);
        }
    }

    @Test
    void preservesExecutionOrderForMultipleToolsInOneModelResponse() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("get_weather", "countries_get_by_name"),
                finalResponse("Done"));

        ChatResult result = orchestrator(model).chat("Weather and country");

        assertThat(result.sources())
                .extracting(source -> source.kind())
                .containsExactly(SourceKind.WEATHER, SourceKind.REST_COUNTRIES);
    }

    @Test
    void attributesTheLocalRagCallback() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("search_cdq_fraud_guard"), finalResponse("CDQ answer"));

        ChatResult result = orchestrator(model).chat("What is Fraud Guard?");

        assertThat(result.sources())
                .extracting(source -> source.kind())
                .containsExactly(SourceKind.CDQ_RAG);
        assertThat(rag.calls).isEqualTo(1);
    }

    @Test
    void executesAtMostThreeToolBatchesAcrossFourModelCalls() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"),
                toolResponse("countries_get_by_name"),
                toolResponse("countries_get_by_name"),
                toolResponse("countries_get_by_name"));

        assertThatThrownBy(() -> orchestrator(model).chat("Keep calling"))
                .isInstanceOf(ToolRoundLimitException.class);
        assertThat(model.prompts).hasSize(4);
        assertThat(countryByName.calls).isEqualTo(3);
    }

    @Test
    void mapsAToolFailureToADependencyFailureWithoutRecordingItsSource() {
        weather.failure = new IllegalStateException("secret upstream failure");
        RecordingChatModel model = new RecordingChatModel(toolResponse("get_weather"));

        assertThatThrownBy(() -> orchestrator(model).chat("Weather"))
                .isInstanceOf(ChatDependencyException.class)
                .hasMessageNotContaining("secret");
        assertThat(weather.calls).isEqualTo(1);
    }

    @Test
    void mapsAWeatherServersOrdinaryErrorTextToADependencyFailure() {
        weather.result =
                "[{\"type\":\"text\",\"text\":\"Some error occured. raw-secret-body test-api-key\"}]";
        RecordingChatModel model =
                new RecordingChatModel(toolResponse("get_weather"), finalResponse("invented answer"));

        assertThatThrownBy(() -> orchestrator(model).chat("Weather"))
                .isInstanceOf(ChatDependencyException.class)
                .hasMessageNotContaining("raw-secret-body")
                .hasMessageNotContaining("test-api-key");
        assertThat(model.prompts).hasSize(1);
        assertThat(weather.calls).isEqualTo(1);
    }

    @Test
    void rejectsABlankFinalModelResponseAsADependencyFailure() {
        RecordingChatModel model = new RecordingChatModel(finalResponse("  "));

        assertThatThrownBy(() -> orchestrator(model).chat("Question"))
                .isInstanceOf(ChatDependencyException.class);
    }

    @Test
    void mapsAModelFailureToASafeDependencyFailureAndLogsOnlyItsCategory(
            CapturedOutput output) {
        ChatModel failingModel = prompt -> {
            throw new IllegalStateException(
                    "raw-model-body fake-model-api-key https://model.example.test?key=fake-model-api-key");
        };

        assertThatThrownBy(() -> orchestrator(failingModel).chat("Question"))
                .isInstanceOf(ChatDependencyException.class)
                .hasMessageNotContaining("fake-model-api-key");
        assertThat(output)
                .contains("Chat model invocation failed; dependency details were suppressed.")
                .doesNotContain("raw-model-body")
                .doesNotContain("fake-model-api-key")
                .doesNotContain("https://model.example.test")
                .doesNotContain("IllegalStateException");
    }

    @Test
    void mapsAnOllamaReadTimeoutToTheSafePublicTimeoutCategory(CapturedOutput output) {
        ChatModel timingOutModel = prompt -> {
            throw new ResourceAccessException(
                    "raw-provider-timeout fake-model-api-key",
                    new SocketTimeoutException("raw socket timeout"));
        };

        assertThatThrownBy(() -> orchestrator(timingOutModel).chat("Question"))
                .isInstanceOf(ChatTimeoutException.class)
                .hasMessageNotContaining("raw-provider-timeout")
                .hasMessageNotContaining("fake-model-api-key");
        assertThat(output)
                .contains("Chat model invocation failed; dependency details were suppressed.")
                .doesNotContain("raw-provider-timeout", "fake-model-api-key", "raw socket timeout");
    }

    @Test
    void rejectsAnEmptyModelResponseAsADependencyFailure() {
        RecordingChatModel model = new RecordingChatModel(new ChatResponse(List.of()));

        assertThatThrownBy(() -> orchestrator(model).chat("Question"))
                .isInstanceOf(ChatDependencyException.class);
    }

    @Test
    void startsTheSecondRequestWithoutTheFirstRequestsMessagesOrToolTranscript() {
        RecordingChatModel model = new RecordingChatModel(
                toolResponse("countries_get_by_name"),
                finalResponse("First answer"),
                finalResponse("Second answer"));
        ChatOrchestrator orchestrator = orchestrator(model);

        orchestrator.chat("FIRST USER MESSAGE");
        ChatResult second = orchestrator.chat("Hi");

        assertThat(second.answer()).isEqualTo("Second answer");
        Prompt secondFirstPrompt = model.prompts.get(2);
        assertThat(secondFirstPrompt.getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(secondFirstPrompt.getInstructions())
                .extracting(Message::getText)
                .containsExactly(EXPECTED_SYSTEM_POLICY, "Hi")
                .doesNotContain("FIRST USER MESSAGE", "Poland facts", "First answer");
    }

    private ChatOrchestrator orchestrator(ChatModel model) {
        AssistantToolCatalog catalog =
                new AssistantToolCatalog(rag, List.of(countryByName, countryByCapital, weather));
        ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
        return new ChatOrchestrator(
                model,
                toolCallingManager,
                catalog,
                new EvidenceRequirementPolicy(),
                new UserFacingAnswerPolicy(),
                "qwen3:4b-instruct-2507-q4_K_M",
                256);
    }

    private static void assertRequiredOptions(Prompt prompt) {
        assertRequiredOptions(prompt, 1);
    }

    private static void assertRequiredOptions(Prompt prompt, int round) {
        OllamaChatOptions options = (OllamaChatOptions) prompt.getOptions();
        assertThat(options.getModel())
                .as("round %s model", round)
                .isEqualTo("qwen3:4b-instruct-2507-q4_K_M");
        assertThat(options.getTemperature()).as("round %s temperature", round).isEqualTo(0.1);
        assertThat(options.getNumPredict()).as("round %s numPredict", round).isEqualTo(256);
        assertThat(options.getThinkOption()).as("round %s thinking option", round).isNull();
        assertThat(options.getToolCallbacks())
                .as("round %s callbacks", round)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(
                        "search_cdq_fraud_guard",
                        "countries_get_by_name",
                        "countries_get_by_capital",
                        "get_weather");
    }

    private static ChatResponse finalResponse(String answer) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    private static ChatResponse toolResponse(String toolName) {
        return toolResponse(new String[] {toolName});
    }

    private static ChatResponse toolResponse(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = java.util.Arrays.stream(toolNames)
                .map(toolName -> new AssistantMessage.ToolCall(
                        "call-" + toolName, "function", toolName, "{}"))
                .toList();
        AssistantMessage assistant =
                AssistantMessage.builder().content("").toolCalls(calls).build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Deque<ChatResponse> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        private RecordingChatModel(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.copy());
            return responses.removeFirst();
        }
    }

    private static final class RecordingTool implements ToolCallback {

        private final ToolDefinition definition;
        private String result;
        private int calls;
        private RuntimeException failure;

        private RecordingTool(String name, String result) {
            this.definition = ToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String input) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
