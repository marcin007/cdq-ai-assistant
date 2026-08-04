package com.cdq.assistant.rag.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cdq.assistant.rag.refresh.CdqKnowledgeDiff;
import com.cdq.assistant.rag.refresh.CdqKnowledgeDiffLine;
import com.cdq.assistant.rag.refresh.CdqKnowledgeScan;
import com.cdq.assistant.rag.refresh.CdqKnowledgeUseCase;
import com.cdq.assistant.rag.refresh.CdqKnowledgeVersion;
import com.cdq.assistant.rag.refresh.CdqKnowledgeView;

@RestController
@RequestMapping("/api/knowledge/cdq")
@ConditionalOnProperty(name = "cdq.rag.enabled", havingValue = "true", matchIfMissing = true)
public final class CdqKnowledgeController {

    private final CdqKnowledgeUseCase useCase;

    public CdqKnowledgeController(CdqKnowledgeUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    CdqKnowledgeResponse status() {
        return response(useCase.status());
    }

    @PostMapping("/scan")
    CdqKnowledgeResponse scan() {
        return response(useCase.scan());
    }

    @PostMapping("/versions/{id}/approve")
    CdqKnowledgeResponse approve(
            @PathVariable UUID id, @Valid @RequestBody ApproveCdqKnowledgeRequest request) {
        return response(useCase.approve(id, request.comment()));
    }

    @PostMapping("/versions/{id}/reject")
    CdqKnowledgeResponse reject(@PathVariable UUID id) {
        return response(useCase.reject(id));
    }

    @PostMapping("/versions/{id}/ingest")
    CdqKnowledgeResponse ingest(@PathVariable UUID id) {
        return response(useCase.ingest(id));
    }

    private static CdqKnowledgeResponse response(CdqKnowledgeView view) {
        return new CdqKnowledgeResponse(
                view.sourceUrl().toString(),
                version(view.active()),
                scan(view.lastScan()),
                candidate(view.candidate(), view.diff()),
                new CdqKnowledgeResponse.Actions(
                        view.canReject(), view.canApprove(), view.canIngest()));
    }

    private static CdqKnowledgeResponse.VersionSummary version(CdqKnowledgeVersion version) {
        if (version == null) {
            return null;
        }
        return new CdqKnowledgeResponse.VersionSummary(
                version.id(), version.snapshotHash(), version.capturedAt(), version.activatedAt());
    }

    private static CdqKnowledgeResponse.ScanSummary scan(CdqKnowledgeScan scan) {
        if (scan == null) {
            return null;
        }
        return new CdqKnowledgeResponse.ScanSummary(
                scan.scannedAt(),
                scan.outcome().name(),
                scan.failureCode() == null ? null : scan.failureCode().name());
    }

    private static CdqKnowledgeResponse.CandidateSummary candidate(
            CdqKnowledgeVersion candidate, CdqKnowledgeDiff diff) {
        if (candidate == null) {
            return null;
        }
        return new CdqKnowledgeResponse.CandidateSummary(
                candidate.id(),
                candidate.status().name(),
                candidate.snapshotHash(),
                candidate.capturedAt(),
                candidate.reviewedAt(),
                candidate.reviewComment(),
                diff(diff));
    }

    private static CdqKnowledgeResponse.DiffSummary diff(CdqKnowledgeDiff diff) {
        if (diff == null) {
            return null;
        }
        List<CdqKnowledgeResponse.DiffLine> lines = diff.lines().stream()
                .map(CdqKnowledgeController::line)
                .toList();
        return new CdqKnowledgeResponse.DiffSummary(diff.addedLines(), diff.removedLines(), lines);
    }

    private static CdqKnowledgeResponse.DiffLine line(CdqKnowledgeDiffLine line) {
        return new CdqKnowledgeResponse.DiffLine(line.type().name(), line.text());
    }
}
