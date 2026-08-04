package com.cdq.assistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CdqModelConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    void resolvesTheRequiredEmbeddingAndPgvectorConfiguration() {
        assertThat(environment.getProperty("spring.ai.ollama.embedding.model"))
                .isEqualTo("qwen3-embedding:0.6b");
        assertThat(environment.getProperty("spring.ai.ollama.init.pull-model-strategy"))
                .isEqualTo("never");
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", Integer.class))
                .isEqualTo(1024);
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.distance-type"))
                .isEqualTo("cosine-distance");
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class))
                .isFalse();
    }
}
