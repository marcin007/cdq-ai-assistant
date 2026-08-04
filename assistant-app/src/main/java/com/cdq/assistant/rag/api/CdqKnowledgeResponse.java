package com.cdq.assistant.rag.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CdqKnowledgeResponse(
        String sourceUrl,
        VersionSummary active,
        ScanSummary lastScan,
        CandidateSummary candidate,
        Actions actions) {

    public record VersionSummary(UUID id, String snapshotHash, Instant capturedAt, Instant activatedAt) {
    }

    public record ScanSummary(Instant scannedAt, String outcome, String failureCode) {
    }

    public record CandidateSummary(
            UUID id,
            String status,
            String snapshotHash,
            Instant capturedAt,
            Instant reviewedAt,
            String reviewComment,
            DiffSummary diff) {
    }

    public record DiffSummary(int addedLines, int removedLines, List<DiffLine> lines) {

        public DiffSummary {
            lines = List.copyOf(lines);
        }
    }

    public record DiffLine(String type, String text) {
    }

    public record Actions(boolean canReject, boolean canApprove, boolean canIngest) {
    }
}
