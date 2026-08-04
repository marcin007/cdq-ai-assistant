package com.cdq.assistant.rag;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

class CdqFraudGuardSearchToolTest {

    @Test
    void exposesTheExactSpringAiToolName() {
        CdqFraudGuardSearchTool tool = new CdqFraudGuardSearchTool(new FakeCdqVectorRepository());

        assertThat(ToolCallbacks.from(tool))
                .singleElement()
                .satisfies(callback -> assertThat(callback.getToolDefinition().name())
                        .isEqualTo("search_cdq_fraud_guard"));
    }

    @Test
    void returnsAtMostFourRelevantCdqExcerptsWithMetadata() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        repository.seed(
                "cdq-fraud-guard",
                searchResult("chunk-zero", 0, 0.95),
                searchResult("chunk-one", 1, 0.90),
                searchResult("chunk-two", 2, 0.80),
                searchResult("chunk-three", 3, 0.70),
                searchResult("fifth-result-must-not-appear", 4, 0.60),
                searchResult("below-threshold-must-not-appear", 5, 0.49));
        repository.seed("other-source", searchResult("other-source-must-not-appear", 0, 0.99));
        CdqFraudGuardSearchTool tool = new CdqFraudGuardSearchTool(repository);

        String result = tool.searchCdqFraudGuard("How does Trust Score work?");

        assertThat(result)
                .contains("Excerpt: chunk-zero")
                .contains("Excerpt: chunk-one")
                .contains("Excerpt: chunk-two")
                .contains("Excerpt: chunk-three")
                .contains("sourceId: cdq-fraud-guard")
                .contains("sourceUrl: https://www.cdq.com/products/cdq-fraud-guard")
                .contains("capturedAt: 2026-07-26T08:22:11Z")
                .contains("snapshotHash: snapshot-hash")
                .contains("chunkIndex: 0")
                .doesNotContain("fifth-result-must-not-appear")
                .doesNotContain("below-threshold-must-not-appear")
                .doesNotContain("other-source-must-not-appear");
        assertThat(result.split("Excerpt: ", -1)).hasSize(5);
    }

    @Test
    void returnsTheExactNoResultSentenceWhenNothingMeetsTheThreshold() {
        FakeCdqVectorRepository repository = new FakeCdqVectorRepository();
        repository.seed("cdq-fraud-guard", searchResult("too-distant", 0, 0.49));
        CdqFraudGuardSearchTool tool = new CdqFraudGuardSearchTool(repository);

        String result = tool.searchCdqFraudGuard("unrelated query");

        assertThat(result).isEqualTo("No relevant CDQ Fraud Guard information was found.");
    }

    private static Document searchResult(String text, int chunkIndex, double score) {
        return Document.builder()
                .id("id-" + chunkIndex)
                .text(text)
                .metadata(Map.of(
                        "sourceId", "cdq-fraud-guard",
                        "sourceUrl", "https://www.cdq.com/products/cdq-fraud-guard",
                        "capturedAt", "2026-07-26T08:22:11Z",
                        "snapshotHash", "snapshot-hash",
                        "chunkIndex", chunkIndex))
                .score(score)
                .build();
    }
}
