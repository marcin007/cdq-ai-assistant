package com.cdq.countries.country;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "countries.api-key=contract-test-key"
})
class CountriesMcpStreamableHttpContractTest {

    private static HttpServer countriesStub;
    private static int countriesStubPort;
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void countryProperties(DynamicPropertyRegistry registry) {
        try {
            countriesStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            countriesStubPort = countriesStub.getAddress().getPort();
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        countriesStub.createContext("/names.common/Poland", exchange -> {
            byte[] body = """
                    {"data":{"objects":[{
                      "names":{"common":"Poland","official":"Republic of Poland"},
                      "codes":{"alpha_2":"PL","alpha_3":"POL"},
                      "capitals":[{"name":"Warsaw","attributes":{"primary":true}}],
                      "region":"Europe","subregion":"Central Europe","population":38000000,
                      "currencies":[{"code":"PLN","name":"Polish zloty","symbol":"zł"}],
                      "languages":[{"iso_639_3":"pol","bcp47":"pl","name":"Polish","native_name":"polski"}]
                    }]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        countriesStub.createContext("/names.common/Failure", exchange -> {
            byte[] body = "{\"detail\":\"raw-secret-body\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        countriesStub.start();
        registry.add("countries.api-base-url", () -> "http://127.0.0.1:" + countriesStubPort);
    }

    @AfterAll
    static void stopCountriesStub() {
        if (countriesStub != null) {
            countriesStub.stop(0);
        }
        System.clearProperty("countries.stub.port");
    }

    @Test
    void initializesListsBothToolsAndInvokesTheNameToolThroughStreamableHttp() throws Exception {
        HttpResponse<String> initialize = post(null, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "contract-test", "version", "1.0"))));

        assertThat(initialize.statusCode()).isEqualTo(200);
        String sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();
        post(sessionId, Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

        HttpResponse<String> toolsList = post(sessionId, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));
        JsonNode tools = responseJson(toolsList).path("result").path("tools");
        assertThat(toolsList.statusCode()).isEqualTo(200);
        assertThat(tools).extracting(tool -> tool.path("name").asString()).containsExactlyInAnyOrder(
                "countries_get_by_name", "countries_get_by_capital");
        assertInputSchema(toolNamed(tools, "countries_get_by_name"), "name");
        assertInputSchema(toolNamed(tools, "countries_get_by_capital"), "capital");

        HttpResponse<String> invocation = post(sessionId, Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "countries_get_by_name", "arguments", Map.of("name", "Poland"))));
        JsonNode response = responseJson(invocation);

        assertThat(invocation.statusCode()).isEqualTo(200);
        assertThat(response.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(invocation.body()).contains("Poland").doesNotContain("contract-test-key");
    }

    @Test
    void marksAnUpstreamDependencyFailureAsAnMcpErrorWithoutLeakingSecrets() throws Exception {
        HttpResponse<String> initialize = post(null, Map.of(
                "jsonrpc", "2.0", "id", 10, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "failure-contract-test", "version", "1.0"))));
        String sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();
        post(sessionId, Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

        HttpResponse<String> invocation = post(sessionId, Map.of(
                "jsonrpc", "2.0", "id", 11, "method", "tools/call",
                "params", Map.of("name", "countries_get_by_name", "arguments", Map.of("name", "Failure"))));
        JsonNode response = responseJson(invocation);

        assertThat(invocation.statusCode()).isEqualTo(200);
        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(invocation.body())
                .doesNotContain("raw-secret-body")
                .doesNotContain("contract-test-key");
    }

    private HttpResponse<String> post(String sessionId, Map<String, Object> requestBody) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)));
        if (sessionId != null) {
            request.header("MCP-Session-Id", sessionId);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode responseJson(HttpResponse<String> response) throws tools.jackson.core.JacksonException {
        String json = response.body().lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .findFirst()
                .orElse(response.body());
        return JSON.readTree(json);
    }

    private JsonNode toolNamed(JsonNode tools, String name) {
        return java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                .filter(tool -> name.equals(tool.path("name").asString()))
                .findFirst()
                .orElseThrow();
    }

    private void assertInputSchema(JsonNode tool, String propertyName) {
        JsonNode inputSchema = tool.path("inputSchema");
        JsonNode properties = inputSchema.path("properties");
        assertThat(inputSchema.path("type").asString()).isEqualTo("object");
        assertThat(properties.properties().stream().map(Map.Entry::getKey)).containsExactly(propertyName);
        assertThat(properties.path(propertyName).path("type").asString()).isEqualTo("string");
        assertThat(inputSchema.path("required")).extracting(JsonNode::asString).containsExactly(propertyName);
    }
}
