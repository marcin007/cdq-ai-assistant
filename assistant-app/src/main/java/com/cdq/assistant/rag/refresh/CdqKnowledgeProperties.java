package com.cdq.assistant.rag.refresh;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cdq.knowledge")
public record CdqKnowledgeProperties(
        URI sourceUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes) {

    private static final URI CANONICAL_SOURCE =
            URI.create("https://www.cdq.com/products/cdq-fraud-guard");

    public CdqKnowledgeProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("cdq.knowledge.max-response-bytes must be positive");
        }
    }

    public URI validatedSource(boolean knowledgeTestProfile) {
        if (CANONICAL_SOURCE.equals(sourceUrl)) {
            return sourceUrl;
        }
        if (knowledgeTestProfile && isHttpLoopback(sourceUrl)) {
            return sourceUrl;
        }
        throw new IllegalStateException("cdq.knowledge.source-url is not allowed");
    }

    private static boolean isHttpLoopback(URI source) {
        if (source == null
                || !"http".equalsIgnoreCase(source.getScheme())
                || source.getUserInfo() != null) {
            return false;
        }
        String host = source.getHost();
        return "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("cdq.knowledge." + name + " must be positive");
        }
    }
}
