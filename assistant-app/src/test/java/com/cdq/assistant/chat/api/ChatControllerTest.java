package com.cdq.assistant.chat.api;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cdq.assistant.chat.application.ChatDependencyException;
import com.cdq.assistant.chat.application.ChatGroundingException;
import com.cdq.assistant.chat.application.ChatService;
import com.cdq.assistant.chat.application.ChatResult;
import com.cdq.assistant.chat.application.ChatUseCase;
import com.cdq.assistant.chat.application.ChatTimeoutException;
import com.cdq.assistant.chat.application.ToolRoundLimitException;
import com.cdq.assistant.chat.tool.SourceKind;
import com.cdq.assistant.chat.tool.SourceRecord;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private StubChatUseCase service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new StubChatUseCase();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsOnlyTheAnswerAndOrderedPublicSourceRecords() throws Exception {
        service.result = new ChatResult(
                "Warsaw is 17.5 °C.",
                List.of(
                        new SourceRecord(
                                SourceKind.REST_COUNTRIES,
                                "REST Countries v5",
                                URI.create("https://restcountries.com/")),
                        new SourceRecord(
                                SourceKind.WEATHER,
                                "WeatherAPI via semdin/mcp-weather",
                                URI.create("https://github.com/semdin/mcp-weather"))));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  country and weather  \"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value("Warsaw is 17.5 °C."))
                .andExpect(jsonPath("$.sources[0].kind").value("REST_COUNTRIES"))
                .andExpect(jsonPath("$.sources[0].label").value("REST Countries v5"))
                .andExpect(jsonPath("$.sources[0].url").value("https://restcountries.com/"))
                .andExpect(jsonPath("$.sources[1].kind").value("WEATHER"))
                .andExpect(jsonPath("$.sources[1].label").value("WeatherAPI via semdin/mcp-weather"))
                .andExpect(jsonPath("$.sources[1].url").value("https://github.com/semdin/mcp-weather"))
                .andExpect(jsonPath("$.sources[0].type").doesNotExist())
                .andExpect(jsonPath("$.session").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(service.lastMessage)
                .isEqualTo("country and weather");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void returnsARfc9457BadRequestForInvalidJsonOrMessage(String description, String body)
            throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("The request body must contain a message of 1 to 2000 characters."))
                .andExpect(jsonPath("$.instance").value("/api/chat"));
    }

    @Test
    void returnsARfc9457BadRequestWhenTheBodyIsMissing() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.instance").value("/api/chat"));
    }

    @Test
    void returnsASafeServiceUnavailableProblemForModelToolAndRoundFailures()
            throws Exception {
        service.failures.add(new ChatDependencyException());
        service.failures.add(new ToolRoundLimitException());

        assertDependencyProblem();
        assertDependencyProblem();
    }

    @Test
    void returnsASafeGatewayTimeoutProblemForTheOverallDeadline() throws Exception {
        service.failures.add(new ChatTimeoutException());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"failure\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Request timed out"))
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.detail")
                        .value("The assistant did not complete the request within 45 seconds."))
                .andExpect(jsonPath("$.instance").value("/api/chat"));
    }

    @Test
    void returnsASafeUnverifiedAnswerProblemWithoutLeakingTheModelAnswer() throws Exception {
        service.failures.add(new ChatGroundingException());

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"What is Germany's capital?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Answer not verified"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail")
                        .value("The assistant could not verify the answer with the required sources."))
                .andExpect(jsonPath("$.instance").value("/api/chat"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Berlin"))));
    }

    @Test
    void mapsAGroundingFailureFromTheRealChatServiceToAnUnverifiedAnswerProblem()
            throws Exception {
        ChatGroundingException failure = new ChatGroundingException();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService realService = new ChatService(message -> {
                throw failure;
            }, executor, Duration.ofSeconds(1));
            MockMvc realMvc = MockMvcBuilders.standaloneSetup(new ChatController(realService))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

            realMvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"What is Germany's capital?\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("about:blank"))
                    .andExpect(jsonPath("$.title").value("Answer not verified"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.detail")
                            .value("The assistant could not verify the answer with the required sources."))
                    .andExpect(jsonPath("$.instance").value("/api/chat"));
        }
    }

    @Test
    void mapsATimeoutFromTheRealChatServiceToTheFixedGatewayTimeoutProblem()
            throws Exception {
        ChatTimeoutException failure = new ChatTimeoutException();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService realService = new ChatService(message -> {
                throw failure;
            }, executor, Duration.ofSeconds(1));
            MockMvc realMvc = MockMvcBuilders.standaloneSetup(new ChatController(realService))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

            realMvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"failure\"}"))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("about:blank"))
                    .andExpect(jsonPath("$.title").value("Request timed out"))
                    .andExpect(jsonPath("$.status").value(504))
                    .andExpect(jsonPath("$.detail")
                            .value("The assistant did not complete the request within 45 seconds."))
                    .andExpect(jsonPath("$.instance").value("/api/chat"));
        }
    }

    private void assertDependencyProblem() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"failure\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Dependency unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail")
                        .value("The assistant could not complete the request because a required dependency failed."))
                .andExpect(jsonPath("$.instance").value("/api/chat"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ToolRoundLimitException"))));
    }

    private static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("malformed JSON", "{\"message\":"),
                Arguments.of("missing message", "{}"),
                Arguments.of("null message", "{\"message\":null}"),
                Arguments.of("blank message", "{\"message\":\"   \"}"),
                Arguments.of("trimmed message over 2000 characters", jsonMessage("x".repeat(2001))));
    }

    private static String jsonMessage(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    private static final class StubChatUseCase implements ChatUseCase {

        private final Deque<RuntimeException> failures = new ArrayDeque<>();
        private ChatResult result = new ChatResult("unused", List.of());
        private String lastMessage;

        @Override
        public ChatResult chat(String message) {
            lastMessage = message;
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            return result;
        }
    }
}
