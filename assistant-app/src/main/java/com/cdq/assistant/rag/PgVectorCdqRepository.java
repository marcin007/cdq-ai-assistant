package com.cdq.assistant.rag;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

public final class PgVectorCdqRepository implements CdqVectorRepository {

    private final PgVectorStore vectorStore;

    private final JdbcTemplate jdbcTemplate;

    private final TransactionOperations transactions;

    public PgVectorCdqRepository(
            PgVectorStore vectorStore, JdbcTemplate jdbcTemplate, TransactionOperations transactions) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    @Override
    public Set<String> snapshotHashes(String sourceId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT DISTINCT metadata->>'snapshotHash'
                        FROM vector_store
                        WHERE metadata->>'sourceId' = ?
                        """,
                        (resultSet, rowNumber) -> resultSet.getString(1),
                        sourceId)
                .stream()
                .map(hash -> hash == null ? MISSING_SNAPSHOT_HASH : hash)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void replaceSource(String sourceId, List<Document> documents) {
        transactions.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'sourceId' = ?",
                    sourceId);
            vectorStore.add(documents);
        });
    }

    @Override
    public List<Document> search(String query, int topK, double similarityThreshold, String sourceId) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(new FilterExpressionBuilder().eq("sourceId", sourceId).build())
                .build();
        return vectorStore.similaritySearch(request);
    }
}
