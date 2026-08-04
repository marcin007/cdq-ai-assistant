package com.cdq.assistant.chat.api;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.cdq.assistant.chat.application.ChatOrchestrator;
import com.cdq.assistant.chat.application.ChatService;
import com.cdq.assistant.chat.application.EvidenceRequirementPolicy;
import com.cdq.assistant.chat.application.UserFacingAnswerPolicy;
import com.cdq.assistant.chat.tool.AssistantToolCatalog;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatHttpCancellationTest {

    private static final String MODEL = "qwen3:4b-instruct-2507-q4_K_M";

    @Test
    void cancelsALaterOllamaHttpExchangeAtTheOuterDeadline504() throws Exception {
        try (StallingOllamaServer ollama = new StallingOllamaServer();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatService service = new ChatService(
                    orchestrator(ollama.baseUrl()), executor, Duration.ofMillis(750));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                    .setControllerAdvice(new ApiExceptionHandler())
                    .build();

            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"What is Germany's capital?\"}"))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.title").value("Request timed out"))
                    .andExpect(jsonPath("$.status").value(504));

            assertThat(ollama.laterRoundStarted()).isTrue();
            assertThat(ollama.exchangeClosed()).isTrue();
            assertThat(ollama.requestCount()).isEqualTo(2);
        }
    }

    private static ChatOrchestrator orchestrator(String baseUrl) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withReadTimeout(Duration.ofSeconds(5)));
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .build();
        RetryTemplate noRetry =
                new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder().model(MODEL).build())
                .retryTemplate(noRetry)
                .build();
        ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder().build();
        AssistantToolCatalog catalog = new AssistantToolCatalog(
                tool("search_cdq_fraud_guard", "unused"),
                List.of(
                        tool("countries_get_by_name", "Germany: Berlin"),
                        tool("countries_get_by_capital", "unused"),
                        tool(
                                "get_weather",
                                "[{\"type\":\"text\",\"text\":\"the weather in Berlin is currently: 18\"}]")));
        return new ChatOrchestrator(
                chatModel,
                toolCallingManager,
                catalog,
                new EvidenceRequirementPolicy(),
                new UserFacingAnswerPolicy(),
                MODEL,
                256);
    }

    private static ToolCallback tool(String name, String result) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                return result;
            }
        };
    }

    private static final class StallingOllamaServer implements AutoCloseable {

        private static final String TOOL_CALL_RESPONSE = """
                {"model":"qwen3:4b-instruct-2507-q4_K_M",\
                "created_at":"2026-08-04T12:00:00Z",\
                "message":{"role":"assistant","content":"",\
                "tool_calls":[{"function":{"name":"countries_get_by_name",\
                "arguments":{"name":"Germany"}}}]},\
                "done":true,"done_reason":"stop"}
                """;

        private final CountDownLatch laterRoundStarted = new CountDownLatch(1);
        private final CountDownLatch exchangeClosed = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger();
        private final DisposableServer server;

        private StallingOllamaServer() {
            server = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> request.receive().then(Mono.defer(() -> {
                        int round = requestCount.incrementAndGet();
                        if (round == 1) {
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just(TOOL_CALL_RESPONSE))
                                    .then();
                        }
                        laterRoundStarted.countDown();
                        request.withConnection(connection ->
                                connection.onDispose(exchangeClosed::countDown));
                        return response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.never())
                                .then();
                    })))
                    .bindNow(Duration.ofSeconds(5));
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.port();
        }

        private boolean laterRoundStarted() throws InterruptedException {
            return laterRoundStarted.await(2, TimeUnit.SECONDS);
        }

        private boolean exchangeClosed() throws InterruptedException {
            return exchangeClosed.await(2, TimeUnit.SECONDS);
        }

        private int requestCount() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.disposeNow(Duration.ofSeconds(2));
        }
    }
}
