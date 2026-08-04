package com.cdq.assistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;

final class FakeCdqVectorRepository implements CdqVectorRepository {

    private final Map<String, List<Document>> documentsBySource = new LinkedHashMap<>();

    private int replacementCount;

    void seed(String sourceId, Document... documents) {
        documentsBySource.put(sourceId, new ArrayList<>(List.of(documents)));
    }

    List<Document> documents(String sourceId) {
        return List.copyOf(documentsBySource.getOrDefault(sourceId, List.of()));
    }

    int replacementCount() {
        return replacementCount;
    }

    @Override
    public Set<String> snapshotHashes(String sourceId) {
        Set<String> hashes = new LinkedHashSet<>();
        for (Document document : documents(sourceId)) {
            Object hash = document.getMetadata().get("snapshotHash");
            if (hash instanceof String text) {
                hashes.add(text);
            }
            else {
                hashes.add(MISSING_SNAPSHOT_HASH);
            }
        }
        return Set.copyOf(hashes);
    }

    @Override
    public void replaceSource(String sourceId, List<Document> documents) {
        documentsBySource.put(sourceId, new ArrayList<>(documents));
        replacementCount++;
    }

    @Override
    public List<Document> search(String query, int topK, double similarityThreshold, String sourceId) {
        return documents(sourceId).stream()
                .filter(document -> document.getScore() != null && document.getScore() >= similarityThreshold)
                .sorted(Comparator.comparing(Document::getScore).reversed())
                .limit(topK)
                .toList();
    }
}
