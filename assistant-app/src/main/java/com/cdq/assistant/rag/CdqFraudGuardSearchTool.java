package com.cdq.assistant.rag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class CdqFraudGuardSearchTool {

    public static final String NO_RESULT = "No relevant CDQ Fraud Guard information was found.";

    private final CdqVectorRepository vectorRepository;

    public CdqFraudGuardSearchTool(CdqVectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    @Tool(
            name = "search_cdq_fraud_guard",
            description = "Search the curated CDQ Fraud Guard product knowledge for relevant information.")
    public String searchCdqFraudGuard(
            @ToolParam(description = "Question or search query about CDQ Fraud Guard") String query) {
        List<Document> documents =
                vectorRepository.search(query, 4, 0.50, CdqKnowledgeLoader.SOURCE_ID);
        if (documents.isEmpty()) {
            return NO_RESULT;
        }
        return documents.stream().map(CdqFraudGuardSearchTool::format).collect(Collectors.joining("\n\n"));
    }

    private static String format(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return """
                Excerpt: %s
                Metadata:
                sourceId: %s
                sourceUrl: %s
                capturedAt: %s
                snapshotHash: %s
                chunkIndex: %s"""
                .formatted(
                        document.getText(),
                        metadata.get("sourceId"),
                        metadata.get("sourceUrl"),
                        metadata.get("capturedAt"),
                        metadata.get("snapshotHash"),
                        metadata.get("chunkIndex"));
    }
}
