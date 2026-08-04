package com.cdq.assistant.rag;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;

public final class CdqKnowledgeIngestor {

    private final CdqVectorRepository vectorRepository;

    public CdqKnowledgeIngestor(CdqVectorRepository vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    public CdqIngestionOutcome ingest(CdqKnowledgeSnapshot snapshot) {
        if (vectorRepository.snapshotHashes(snapshot.sourceId()).equals(Set.of(snapshot.snapshotHash()))) {
            return CdqIngestionOutcome.SKIPPED;
        }
        List<Document> documents = IntStream.range(0, snapshot.chunks().size())
                .mapToObj(index -> snapshot.chunks().get(index).mutate()
                        .id(CdqDocumentIds.forSnapshotChunk(snapshot.snapshotHash(), index))
                        .build())
                .toList();
        vectorRepository.replaceSource(snapshot.sourceId(), documents);
        return CdqIngestionOutcome.REPLACED;
    }
}
