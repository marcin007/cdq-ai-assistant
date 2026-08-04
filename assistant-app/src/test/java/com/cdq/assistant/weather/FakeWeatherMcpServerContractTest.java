package com.cdq.assistant.weather;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FakeWeatherMcpServerContractTest {

    @Test
    void rejectsAWrongToolNameEvenWhenCityIsOtherwiseValid(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                """
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"not-get-weather","arguments":{"city":"Warsaw"}}}
                        """);
    }

    @Test
    void rejectsANonStringCityEvenWhenTheToolNameIsCorrect(@TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                """
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get-weather","arguments":{"city":17.5}}}
                        """);
    }

    private static void assertRejected(Path temporaryDirectory, String request) throws Exception {
        Path processLog = temporaryDirectory.resolve("fake-weather.log");
        ProcessBuilder processBuilder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("surefire.test.class.path"),
                        FakeWeatherMcpServer.class.getName())
                .redirectErrorStream(true);
        processBuilder.environment().put("WEATHER_MCP_PROCESS_LOG", processLog.toString());
        processBuilder.environment().put("WEATHER_API_URL", "https://api.weatherapi.com/v1/current.json");
        processBuilder.environment().put("WEATHER_API_KEY", "test-weather-key");
        Process process = processBuilder.start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        String response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        assertThat(response).contains("\"isError\":true").doesNotContain("17.5");
        assertThat(Files.readString(processLog)).contains("rejected-call");
    }
}
