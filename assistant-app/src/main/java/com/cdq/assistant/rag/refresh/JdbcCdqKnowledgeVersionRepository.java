package com.cdq.assistant.rag.refresh;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcCdqKnowledgeVersionRepository implements CdqKnowledgeVersionRepository {

    private static final String VERSION_COLUMNS = """
            id, source_id, source_url, status, content, snapshot_hash,
            captured_at, created_at, reviewed_at, review_comment, activated_at, superseded_by
            """;

    private static final String SCAN_COLUMNS = """
            id, source_id, scanned_at, outcome, remote_hash, candidate_version_id, failure_code
            """;

    private static final RowMapper<CdqKnowledgeVersion> VERSION_ROW_MAPPER =
            JdbcCdqKnowledgeVersionRepository::mapVersion;

    private static final RowMapper<CdqKnowledgeScan> SCAN_ROW_MAPPER =
            JdbcCdqKnowledgeVersionRepository::mapScan;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCdqKnowledgeVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CdqKnowledgeVersion> findActive(String sourceId) {
        return findVersionBySourceAndStatus(sourceId, "status = 'ACTIVE'", false);
    }

    @Override
    public Optional<CdqKnowledgeVersion> findActiveForUpdate(String sourceId) {
        return findVersionBySourceAndStatus(sourceId, "status = 'ACTIVE'", true);
    }

    @Override
    public Optional<CdqKnowledgeVersion> findOpenCandidate(String sourceId) {
        return findVersionBySourceAndStatus(sourceId, "status IN ('PENDING_REVIEW', 'APPROVED')", false);
    }

    @Override
    public Optional<CdqKnowledgeVersion> findOpenCandidateForUpdate(String sourceId) {
        return findVersionBySourceAndStatus(sourceId, "status IN ('PENDING_REVIEW', 'APPROVED')", true);
    }

    @Override
    public Optional<CdqKnowledgeVersion> findVersion(UUID id) {
        return findVersion(id, false);
    }

    @Override
    public Optional<CdqKnowledgeVersion> findVersionForUpdate(UUID id) {
        return findVersion(id, true);
    }

    @Override
    public Optional<CdqKnowledgeScan> findLatestScan(String sourceId) {
        return jdbcTemplate.query(
                        "SELECT " + SCAN_COLUMNS
                                + " FROM cdq_knowledge_scan"
                                + " WHERE source_id = ?"
                                + " ORDER BY scanned_at DESC, scan_sequence DESC"
                                + " LIMIT 1",
                        SCAN_ROW_MAPPER,
                        sourceId)
                .stream()
                .findFirst();
    }

    @Override
    public void insertVersion(CdqKnowledgeVersion version) {
        jdbcTemplate.update(
                """
                INSERT INTO cdq_knowledge_version (
                    id, source_id, source_url, status, content, snapshot_hash,
                    captured_at, created_at, reviewed_at, review_comment, activated_at, superseded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.id(),
                version.sourceId(),
                version.sourceUrl().toString(),
                version.status().name(),
                version.content(),
                version.snapshotHash(),
                timestamp(version.capturedAt()),
                timestamp(version.createdAt()),
                timestamp(version.reviewedAt()),
                version.reviewComment(),
                timestamp(version.activatedAt()),
                version.supersededBy());
    }

    @Override
    public void insertScan(CdqKnowledgeScan scan) {
        jdbcTemplate.update(
                """
                INSERT INTO cdq_knowledge_scan (
                    id, source_id, scanned_at, outcome, remote_hash,
                    candidate_version_id, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                scan.id(),
                scan.sourceId(),
                timestamp(scan.scannedAt()),
                scan.outcome().name(),
                scan.remoteHash(),
                scan.candidateVersionId(),
                scan.failureCode() == null ? null : scan.failureCode().name());
    }

    @Override
    public int transition(
            UUID id,
            CdqKnowledgeVersionStatus expected,
            CdqKnowledgeVersionStatus target,
            Instant eventTime,
            String reviewComment,
            UUID supersededBy) {
        return jdbcTemplate.update(
                """
                UPDATE cdq_knowledge_version
                SET status = ?,
                    reviewed_at = CASE
                        WHEN ? IN ('APPROVED', 'REJECTED') THEN ?
                        ELSE reviewed_at
                    END,
                    review_comment = CASE
                        WHEN ? IN ('APPROVED', 'REJECTED') THEN ?
                        ELSE review_comment
                    END,
                    activated_at = CASE
                        WHEN ? = 'ACTIVE' THEN ?
                        ELSE activated_at
                    END,
                    superseded_by = CASE
                        WHEN ? = 'SUPERSEDED' THEN ?
                        ELSE superseded_by
                    END
                WHERE id = ? AND status = ?
                """,
                target.name(),
                target.name(),
                timestamp(eventTime),
                target.name(),
                reviewComment,
                target.name(),
                timestamp(eventTime),
                target.name(),
                supersededBy,
                id,
                expected.name());
    }

    private Optional<CdqKnowledgeVersion> findVersionBySourceAndStatus(
            String sourceId, String statusPredicate, boolean forUpdate) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM cdq_knowledge_version"
                + " WHERE source_id = ? AND " + statusPredicate
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, VERSION_ROW_MAPPER, sourceId).stream().findFirst();
    }

    private Optional<CdqKnowledgeVersion> findVersion(UUID id, boolean forUpdate) {
        String sql = "SELECT " + VERSION_COLUMNS
                + " FROM cdq_knowledge_version WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, VERSION_ROW_MAPPER, id).stream().findFirst();
    }

    private static CdqKnowledgeVersion mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CdqKnowledgeVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_id"),
                URI.create(resultSet.getString("source_url")),
                CdqKnowledgeVersionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("content"),
                resultSet.getString("snapshot_hash"),
                instant(resultSet, "captured_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "reviewed_at"),
                resultSet.getString("review_comment"),
                instant(resultSet, "activated_at"),
                resultSet.getObject("superseded_by", UUID.class));
    }

    private static CdqKnowledgeScan mapScan(ResultSet resultSet, int rowNumber) throws SQLException {
        String failureCode = resultSet.getString("failure_code");
        return new CdqKnowledgeScan(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_id"),
                instant(resultSet, "scanned_at"),
                CdqKnowledgeScanOutcome.valueOf(resultSet.getString("outcome")),
                resultSet.getString("remote_hash"),
                resultSet.getObject("candidate_version_id", UUID.class),
                failureCode == null ? null : CdqKnowledgeFailureCode.valueOf(failureCode));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
