package com.cdq.assistant.weather;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherMcpBootstrapTest {

    @Test
    void bootstrapsOnlyATemporaryPinnedCheckoutWithoutNetworkOrRegistry() throws Exception {
        Path root = repositoryRoot();
        Process process = new ProcessBuilder("sh", root.resolve("scripts/test-bootstrap-weather-mcp.sh").toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("bootstrap-weather-mcp tests passed");
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("scripts/bootstrap-weather-mcp.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Cannot locate repository root");
    }
}
