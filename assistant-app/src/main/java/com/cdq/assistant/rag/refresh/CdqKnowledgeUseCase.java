package com.cdq.assistant.rag.refresh;

import java.util.UUID;

public interface CdqKnowledgeUseCase {

    CdqKnowledgeView status();

    CdqKnowledgeView scan();

    CdqKnowledgeView approve(UUID versionId, String comment);

    CdqKnowledgeView reject(UUID versionId);

    CdqKnowledgeView ingest(UUID versionId);
}
