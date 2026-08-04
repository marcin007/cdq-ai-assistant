package com.cdq.assistant.rag.refresh;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CdqKnowledgeVersionRepository {

    Optional<CdqKnowledgeVersion> findActive(String sourceId);

    Optional<CdqKnowledgeVersion> findActiveForUpdate(String sourceId);

    Optional<CdqKnowledgeVersion> findOpenCandidate(String sourceId);

    Optional<CdqKnowledgeVersion> findOpenCandidateForUpdate(String sourceId);

    Optional<CdqKnowledgeVersion> findVersion(UUID id);

    Optional<CdqKnowledgeVersion> findVersionForUpdate(UUID id);

    Optional<CdqKnowledgeScan> findLatestScan(String sourceId);

    void insertVersion(CdqKnowledgeVersion version);

    void insertScan(CdqKnowledgeScan scan);

    int transition(
            UUID id,
            CdqKnowledgeVersionStatus expected,
            CdqKnowledgeVersionStatus target,
            Instant eventTime,
            String reviewComment,
            UUID supersededBy);
}
