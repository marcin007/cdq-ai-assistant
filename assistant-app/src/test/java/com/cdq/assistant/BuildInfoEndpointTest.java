package com.cdq.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
        classes = AssistantApplication.class,
        properties = {
                "BUILD_COMMIT=0123456789abcdef0123456789abcdef01234567",
                "BUILD_WORKTREE_CLEAN=true",
                "POSTGRES_HOST=example.invalid",
                "POSTGRES_PORT=25432",
                "POSTGRES_DB=fixture_db",
                "POSTGRES_USER=fixture_user",
                "POSTGRES_PASSWORD=fixture_password"
        }
)
class BuildInfoEndpointTest {

    @Autowired
    private InfoEndpoint infoEndpoint;

    @Autowired
    private Environment environment;

    @Test
    void exposesTheRunningBuildAttestationOnTheInfoEndpoint() {
        assertThat(infoEndpoint.info())
                .containsEntry("build", Map.of(
                        "commit", "0123456789abcdef0123456789abcdef01234567",
                        "worktreeClean", true
                ));
    }

    @Test
    void resolvesAllOptionalDatabaseOverrides() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://example.invalid:25432/fixture_db");
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("fixture_user");
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("fixture_password");
    }
}
