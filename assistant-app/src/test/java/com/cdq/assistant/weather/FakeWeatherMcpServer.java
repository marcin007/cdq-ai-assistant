package com.cdq.assistant.weather;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.json.JsonParserFactory;

/** A deterministic JSON-RPC stdio fixture; it never contacts a weather provider. */
public final class FakeWeatherMcpServer {

    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*([^,}]+)");

    private static final Pattern PROTOCOL_VERSION = Pattern.compile("\\\"protocolVersion\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private FakeWeatherMcpServer() {
    }

    public static void main(String[] args) throws IOException {
        Path log = Path.of(System.getenv("WEATHER_MCP_PROCESS_LOG"));
        event(log, "url=" + System.getenv("WEATHER_API_URL"));
        event(log, "key-present=" + !System.getenv("WEATHER_API_KEY").isBlank());

        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String request;
            while ((request = input.readLine()) != null) {
                if (request.contains("\"method\":\"initialize\"")) {
                    event(log, "initialized");
                    respond(output, request, initializeResult(request));
                }
                else if (request.contains("\"method\":\"notifications/initialized\"")) {
                    event(log, "initialized-notification");
                }
                else if (request.contains("\"method\":\"tools/list\"")) {
                    event(log, "list-tools");
                    respond(output, request, """
                            {"tools":[{"name":"get-weather","description":"Get current weather","inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}]}""");
                }
                else if (request.contains("\"method\":\"tools/call\"")) {
                    String city = requestedCity(request);
                    if (city == null) {
                        event(log, "rejected-call");
                        respond(output, request, """
                                {"content":[{"type":"text","text":"provider rejected key=%s"}],"isError":true}"""
                                .formatted(System.getenv("WEATHER_API_KEY")));
                    }
                    else if ("Failure".equals(city)) {
                        event(log, "call:" + city);
                        respond(output, request, """
                                {"content":[{"type":"text","text":"Some error occured."}],"isError":false}""");
                    }
                    else {
                        event(log, "call:" + city);
                        respond(output, request, """
                                {"content":[{"type":"text","text":"the weather in %s is currently: 17.5"}],"isError":false}"""
                                .formatted(city));
                    }
                }
            }
        }
        finally {
            event(log, "stopped");
        }
    }

    private static String initializeResult(String request) {
        Matcher matcher = PROTOCOL_VERSION.matcher(request);
        String protocolVersion = matcher.find() ? matcher.group(1) : "2025-11-25";
        return """
                {"protocolVersion":"%s","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"fake-weather-mcp","version":"1.0.0"}}"""
                .formatted(protocolVersion);
    }

    private static String requestedCity(String request) {
        try {
            Map<String, Object> message = JsonParserFactory.getJsonParser().parseMap(request);
            if (!(message.get("params") instanceof Map<?, ?> params)
                    || !"get-weather".equals(params.get("name"))
                    || !(params.get("arguments") instanceof Map<?, ?> arguments)
                    || !(arguments.get("city") instanceof String city)
                    || (!"Warsaw".equals(city) && !"Failure".equals(city))) {
                return null;
            }
            return city;
        }
        catch (RuntimeException exception) {
            return null;
        }
    }

    private static void respond(BufferedWriter output, String request, String result) throws IOException {
        Matcher matcher = ID.matcher(request);
        if (!matcher.find()) {
            return;
        }
        output.write("{\"jsonrpc\":\"2.0\",\"id\":" + matcher.group(1) + ",\"result\":" + result + "}");
        output.newLine();
        output.flush();
    }

    private static void event(Path log, String value) throws IOException {
        Files.writeString(log, value + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
