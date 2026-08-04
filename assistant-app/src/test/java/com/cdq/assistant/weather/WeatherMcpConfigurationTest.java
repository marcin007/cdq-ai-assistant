package com.cdq.assistant.weather;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@TestPropertySource(properties = "WEATHER_API_KEY=test-weather-key")
@SpringBootTest
class WeatherMcpConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void configuresWeatherStdioWithoutAnNpmLauncherThatWritesToProtocolStdout() {
        assertThat(environment.getProperty("spring.ai.mcp.client.type")).isEqualTo("SYNC");
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.command"))
                .isEqualTo("node");
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.args[0]"))
                .isEqualTo(".local/mcp-weather/node_modules/tsx/dist/cli.mjs");
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.args[1]"))
                .isEqualTo(".local/mcp-weather/src/index.ts");
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.args[2]"))
                .isNull();
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.env.WEATHER_API_URL"))
                .isEqualTo("https://api.weatherapi.com/v1/current.json");
        assertThat(environment.getProperty("spring.ai.mcp.client.stdio.connections.weather.env.WEATHER_API_KEY"))
                .isEqualTo("test-weather-key");
    }
}
