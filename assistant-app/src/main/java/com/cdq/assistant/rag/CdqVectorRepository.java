package com.cdq.assistant.rag;

import java.util.List;
import java.util.Set;

import org.springframework.ai.document.Document;

public interface CdqVectorRepository {

    String MISSING_SNAPSHOT_HASH = "<missing>";

    Set<String> snapshotHashes(String sourceId);

    void replaceSource(String sourceId, List<Document> documents);

    List<Document> search(String query, int topK, double similarityThreshold, String sourceId);
}
