package com.cdq.assistant.rag.refresh;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdqKnowledgePropertiesTest {

    private static final URI CANONICAL_SOURCE =
            URI.create("https://www.cdq.com/products/cdq-fraud-guard");

    @Test
    void acceptsOnlyTheCanonicalHttpsSourceInProduction() {
        assertThat(properties(CANONICAL_SOURCE).validatedSource(false)).isEqualTo(CANONICAL_SOURCE);
    }

    @Test
    void rejectsANoncanonicalSourceInProduction() {
        assertInvalid(URI.create("https://www.cdq.com/products/other"), false);
    }

    @Test
    void rejectsLoopbackHttpWithoutTheKnowledgeTestProfile() {
        assertInvalid(URI.create("http://127.0.0.1:61980/cdq"), false);
        assertInvalid(URI.create("http://[::1]:61980/cdq"), false);
    }

    @Test
    void acceptsIpv4AndIpv6LoopbackHttpWithTheKnowledgeTestProfile() {
        URI ipv4 = URI.create("http://127.0.0.1:61980/cdq");
        URI ipv6 = URI.create("http://[::1]:61980/cdq");

        assertThat(properties(ipv4).validatedSource(true)).isEqualTo(ipv4);
        assertThat(properties(ipv6).validatedSource(true)).isEqualTo(ipv6);
    }

    @Test
    void rejectsNonloopbackSourcesEvenWithTheKnowledgeTestProfile() {
        assertInvalid(URI.create("http://localhost:61980/cdq"), true);
        assertInvalid(URI.create("http://192.168.1.20:61980/cdq"), true);
        assertInvalid(URI.create("https://example.test/cdq"), true);
    }

    private CdqKnowledgeProperties properties(URI source) {
        return new CdqKnowledgeProperties(
                source, Duration.ofSeconds(5), Duration.ofSeconds(15), 2_097_152);
    }

    private void assertInvalid(URI source, boolean knowledgeTestProfile) {
        assertThatThrownBy(() -> properties(source).validatedSource(knowledgeTestProfile))
                .isInstanceOf(IllegalStateException.class);
    }
}
