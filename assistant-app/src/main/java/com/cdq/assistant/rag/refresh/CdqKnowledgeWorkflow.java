package com.cdq.assistant.rag.refresh;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.support.TransactionOperations;

import com.cdq.assistant.rag.CdqKnowledgeIngestor;
import com.cdq.assistant.rag.CdqKnowledgeSnapshot;
import com.cdq.assistant.rag.CdqKnowledgeSnapshotFactory;
import com.cdq.assistant.rag.CdqKnowledgeSource;

import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.INGEST_UNAVAILABLE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.SOURCE_UNAVAILABLE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.VERSION_NOT_FOUND;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeFailureCode.VERSION_STATE_CONFLICT;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.CHANGES_DETECTED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.FAILED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeScanOutcome.UNCHANGED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.APPROVED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.ACTIVE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.INACTIVE;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.PENDING_REVIEW;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.REJECTED;
import static com.cdq.assistant.rag.refresh.CdqKnowledgeVersionStatus.SUPERSEDED;

public final class CdqKnowledgeWorkflow implements CdqKnowledgeUseCase {

    private final String sourceId;
    private final URI sourceUrl;
    private final CdqWebsiteClient websiteClient;
    private final CdqWebsiteContentExtractor extractor;
    private final CdqKnowledgeVersionRepository repository;
    private final CdqKnowledgeDiffer differ;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final CdqKnowledgeIngestor ingestor;
    private final CdqKnowledgeSnapshotFactory snapshotFactory;

    public CdqKnowledgeWorkflow(
            String sourceId,
            URI sourceUrl,
            CdqWebsiteClient websiteClient,
            CdqWebsiteContentExtractor extractor,
            CdqKnowledgeVersionRepository repository,
            CdqKnowledgeDiffer differ,
            TransactionOperations transactions,
            Clock clock,
            CdqKnowledgeIngestor ingestor,
            CdqKnowledgeSnapshotFactory snapshotFactory) {
        this.sourceId = sourceId;
        this.sourceUrl = sourceUrl;
        this.websiteClient = websiteClient;
        this.extractor = extractor;
        this.repository = repository;
        this.differ = differ;
        this.transactions = transactions;
        this.clock = clock;
        this.ingestor = ingestor;
        this.snapshotFactory = snapshotFactory;
    }

    public CdqKnowledgeView status() {
        return transactions.execute(transactionStatus -> viewFromPersistedState());
    }

    public CdqKnowledgeView scan() {
        CdqWebsitePage page;
        CdqExtractedContent extracted;
        try {
            page = websiteClient.fetch();
            extracted = extractor.extract(page.html());
        }
        catch (CdqKnowledgeOperationException exception) {
            recordFailedScan(exception.code());
            throw exception;
        }
        catch (RuntimeException exception) {
            recordFailedScan(SOURCE_UNAVAILABLE);
            throw failure(SOURCE_UNAVAILABLE);
        }

        return transactions.execute(transactionStatus -> scan(page, extracted));
    }

    public CdqKnowledgeView approve(UUID versionId, String reviewComment) {
        return transactions.execute(transactionStatus -> {
            CdqKnowledgeVersion version = versionForReview(versionId);
            if (version.status() != PENDING_REVIEW) {
                throw failure(VERSION_STATE_CONFLICT);
            }
            transitionOrConflict(
                    version.id(), PENDING_REVIEW, APPROVED, clock.instant(), normalize(reviewComment), null);
            return viewFromPersistedState();
        });
    }

    public CdqKnowledgeView reject(UUID versionId) {
        return transactions.execute(transactionStatus -> {
            CdqKnowledgeVersion version = versionForReview(versionId);
            if (version.status() != PENDING_REVIEW && version.status() != APPROVED) {
                throw failure(VERSION_STATE_CONFLICT);
            }
            transitionOrConflict(version.id(), version.status(), REJECTED, clock.instant(), null, null);
            return viewFromPersistedState();
        });
    }

    @Override
    public CdqKnowledgeView ingest(UUID versionId) {
        try {
            return transactions.execute(transactionStatus -> ingestApprovedVersion(versionId));
        }
        catch (CdqKnowledgeOperationException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw failure(INGEST_UNAVAILABLE);
        }
    }

    public CdqKnowledgeSnapshot initializeFrom(CdqKnowledgeSource bundledSource) {
        return transactions.execute(transactionStatus -> {
            CdqKnowledgeVersion active = repository.findActiveForUpdate(sourceId)
                    .orElseGet(() -> bootstrapFrom(bundledSource));
            CdqKnowledgeSnapshot snapshot = snapshotFrom(active);
            ingestor.ingest(snapshot);
            return snapshot;
        });
    }

    private CdqKnowledgeView ingestApprovedVersion(UUID versionId) {
        CdqKnowledgeVersion active = repository.findActiveForUpdate(sourceId)
                .orElseThrow(() -> failure(VERSION_STATE_CONFLICT));
        CdqKnowledgeVersion candidate = versionForReview(versionId);
        if (candidate.status() == ACTIVE) {
            return viewFromPersistedState();
        }
        if (candidate.status() != APPROVED) {
            throw failure(VERSION_STATE_CONFLICT);
        }
        ingestor.ingest(snapshotFrom(candidate));
        Instant activatedAt = clock.instant();
        transitionOrConflict(active.id(), ACTIVE, INACTIVE, activatedAt, null, null);
        transitionOrConflict(candidate.id(), APPROVED, ACTIVE, activatedAt, null, null);
        return viewFromPersistedState();
    }

    private CdqKnowledgeVersion bootstrapFrom(CdqKnowledgeSource bundledSource) {
        CdqKnowledgeSnapshot bundled = bundledSource.load();
        Instant now = clock.instant();
        CdqKnowledgeVersion initial = new CdqKnowledgeVersion(
                UUID.randomUUID(),
                bundled.sourceId(),
                URI.create(bundled.sourceUrl()),
                ACTIVE,
                bundled.content(),
                bundled.snapshotHash(),
                Instant.parse(bundled.capturedAt()),
                now,
                null,
                null,
                now,
                null);
        repository.insertVersion(initial);
        return initial;
    }

    private CdqKnowledgeSnapshot snapshotFrom(CdqKnowledgeVersion version) {
        return snapshotFactory.create(
                version.sourceUrl().toString(),
                version.capturedAt().toString(),
                version.snapshotHash(),
                version.content().getBytes(StandardCharsets.UTF_8));
    }

    private CdqKnowledgeView scan(CdqWebsitePage page, CdqExtractedContent extracted) {
        Instant scannedAt = clock.instant();
        CdqKnowledgeVersion active = repository.findActiveForUpdate(sourceId)
                .orElseThrow(() -> failure(VERSION_STATE_CONFLICT));
        CdqKnowledgeVersion openCandidate = repository.findOpenCandidateForUpdate(sourceId).orElse(null);

        if (extracted.snapshotHash().equals(active.snapshotHash())) {
            if (openCandidate != null) {
                transitionOrConflict(
                        openCandidate.id(), openCandidate.status(), SUPERSEDED, scannedAt, null, null);
            }
            repository.insertScan(scanEvent(scannedAt, UNCHANGED, extracted.snapshotHash(), null, null));
            return viewFromPersistedState();
        }

        if (openCandidate != null && extracted.snapshotHash().equals(openCandidate.snapshotHash())) {
            repository.insertScan(scanEvent(
                    scannedAt, CHANGES_DETECTED, extracted.snapshotHash(), openCandidate.id(), null));
            return viewFromPersistedState();
        }

        UUID candidateId = UUID.randomUUID();
        if (openCandidate != null) {
            transitionOrConflict(
                    openCandidate.id(), openCandidate.status(), SUPERSEDED, scannedAt, null, candidateId);
        }
        CdqKnowledgeVersion candidate = new CdqKnowledgeVersion(
                candidateId,
                sourceId,
                sourceUrl,
                PENDING_REVIEW,
                extracted.text(),
                extracted.snapshotHash(),
                page.capturedAt(),
                scannedAt,
                null,
                null,
                null,
                null);
        repository.insertVersion(candidate);
        repository.insertScan(scanEvent(
                scannedAt, CHANGES_DETECTED, extracted.snapshotHash(), candidateId, null));
        return viewFromPersistedState();
    }

    private CdqKnowledgeVersion versionForReview(UUID versionId) {
        return repository.findVersionForUpdate(versionId)
                .filter(version -> version.sourceId().equals(sourceId))
                .orElseThrow(() -> failure(VERSION_NOT_FOUND));
    }

    private void recordFailedScan(CdqKnowledgeFailureCode failureCode) {
        transactions.executeWithoutResult(transactionStatus -> repository.insertScan(
                scanEvent(clock.instant(), FAILED, null, null, failureCode)));
    }

    private CdqKnowledgeScan scanEvent(
            Instant scannedAt,
            CdqKnowledgeScanOutcome outcome,
            String remoteHash,
            UUID candidateVersionId,
            CdqKnowledgeFailureCode failureCode) {
        return new CdqKnowledgeScan(
                UUID.randomUUID(),
                sourceId,
                scannedAt,
                outcome,
                remoteHash,
                candidateVersionId,
                failureCode);
    }

    private CdqKnowledgeView viewFromPersistedState() {
        CdqKnowledgeVersion active = repository.findActive(sourceId).orElse(null);
        CdqKnowledgeVersion candidate = repository.findOpenCandidate(sourceId).orElse(null);
        CdqKnowledgeScan lastScan = repository.findLatestScan(sourceId).orElse(null);
        CdqKnowledgeDiff diff = active == null || candidate == null
                ? null
                : differ.compare(active.content(), candidate.content());
        boolean canApprove = candidate != null && candidate.status() == PENDING_REVIEW;
        boolean canReject = candidate != null
                && (candidate.status() == PENDING_REVIEW || candidate.status() == APPROVED);
        boolean canIngest = candidate != null && candidate.status() == APPROVED;
        return new CdqKnowledgeView(
                sourceUrl, active, lastScan, candidate, diff, canReject, canApprove, canIngest);
    }

    private void transitionOrConflict(
            UUID id,
            CdqKnowledgeVersionStatus expected,
            CdqKnowledgeVersionStatus target,
            Instant eventTime,
            String reviewComment,
            UUID supersededBy) {
        if (repository.transition(id, expected, target, eventTime, reviewComment, supersededBy) != 1) {
            throw failure(VERSION_STATE_CONFLICT);
        }
    }

    private String normalize(String reviewComment) {
        if (reviewComment == null || reviewComment.isBlank()) {
            return null;
        }
        return reviewComment.trim();
    }

    private CdqKnowledgeOperationException failure(CdqKnowledgeFailureCode code) {
        return new CdqKnowledgeOperationException(code);
    }
}
