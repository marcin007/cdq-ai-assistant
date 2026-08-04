package com.cdq.assistant.rag.refresh;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FakeCdqKnowledgeVersionRepository implements CdqKnowledgeVersionRepository {

    private final Map<UUID, CdqKnowledgeVersion> versions = new LinkedHashMap<>();
    private final List<CdqKnowledgeScan> scans = new ArrayList<>();

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findActive(String sourceId) {
        return findBySourceAndStatus(sourceId, CdqKnowledgeVersionStatus.ACTIVE);
    }

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findActiveForUpdate(String sourceId) {
        return findActive(sourceId);
    }

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findOpenCandidate(String sourceId) {
        return versions.values().stream()
                .filter(version -> version.sourceId().equals(sourceId))
                .filter(version -> version.status() == CdqKnowledgeVersionStatus.PENDING_REVIEW
                        || version.status() == CdqKnowledgeVersionStatus.APPROVED)
                .findFirst();
    }

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findOpenCandidateForUpdate(String sourceId) {
        return findOpenCandidate(sourceId);
    }

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findVersion(UUID id) {
        return Optional.ofNullable(versions.get(id));
    }

    @Override
    public synchronized Optional<CdqKnowledgeVersion> findVersionForUpdate(UUID id) {
        return findVersion(id);
    }

    @Override
    public synchronized Optional<CdqKnowledgeScan> findLatestScan(String sourceId) {
        CdqKnowledgeScan latest = null;
        for (CdqKnowledgeScan scan : scans) {
            if (scan.sourceId().equals(sourceId)
                    && (latest == null || !scan.scannedAt().isBefore(latest.scannedAt()))) {
                latest = scan;
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public synchronized void insertVersion(CdqKnowledgeVersion version) {
        versions.put(version.id(), version);
    }

    @Override
    public synchronized void insertScan(CdqKnowledgeScan scan) {
        scans.add(scan);
    }

    @Override
    public synchronized int transition(
            UUID id,
            CdqKnowledgeVersionStatus expected,
            CdqKnowledgeVersionStatus target,
            Instant eventTime,
            String reviewComment,
            UUID supersededBy) {
        CdqKnowledgeVersion current = versions.get(id);
        if (current == null || current.status() != expected) {
            return 0;
        }
        boolean reviewed = target == CdqKnowledgeVersionStatus.APPROVED
                || target == CdqKnowledgeVersionStatus.REJECTED;
        versions.put(id, new CdqKnowledgeVersion(
                current.id(),
                current.sourceId(),
                current.sourceUrl(),
                target,
                current.content(),
                current.snapshotHash(),
                current.capturedAt(),
                current.createdAt(),
                reviewed ? eventTime : current.reviewedAt(),
                reviewed ? reviewComment : current.reviewComment(),
                target == CdqKnowledgeVersionStatus.ACTIVE ? eventTime : current.activatedAt(),
                target == CdqKnowledgeVersionStatus.SUPERSEDED ? supersededBy : current.supersededBy()));
        return 1;
    }

    public synchronized void seedActive(CdqKnowledgeVersion version) {
        insertVersion(version);
    }

    public synchronized CdqKnowledgeVersion version(UUID id) {
        return findVersion(id).orElseThrow();
    }

    synchronized List<CdqKnowledgeVersion> versions() {
        return List.copyOf(versions.values());
    }

    synchronized List<CdqKnowledgeScan> scans() {
        return List.copyOf(scans);
    }

    private Optional<CdqKnowledgeVersion> findBySourceAndStatus(
            String sourceId, CdqKnowledgeVersionStatus status) {
        return versions.values().stream()
                .filter(version -> version.sourceId().equals(sourceId))
                .filter(version -> version.status() == status)
                .findFirst();
    }
}
