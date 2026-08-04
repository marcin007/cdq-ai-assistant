package com.cdq.assistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cdq.assistant.AssistantApplication;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CdqFlywayAutoMigrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.5-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cdq_assistant")
            .withUsername("cdq")
            .withPassword("cdq");

    @Test
    void appliesTheVectorStoreMigrationBeforeApplicationRunnersExecute() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AssistantApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.autoconfigure.exclude=" + String.join(",",
                                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                                "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
                                "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration",
                                "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration",
                                "org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration"),
                        "cdq.rag.enabled=false",
                        "assistant.chat.enabled=false")
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword())) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT to_regclass('public.vector_store')::text",
                            String.class))
                    .isEqualTo("vector_store");
        }
    }
}
