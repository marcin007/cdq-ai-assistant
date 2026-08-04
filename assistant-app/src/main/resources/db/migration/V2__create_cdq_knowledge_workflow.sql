CREATE TABLE cdq_knowledge_version (
    id UUID PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL CHECK (source_id = 'cdq-fraud-guard'),
    source_url TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN
        ('PENDING_REVIEW','APPROVED','ACTIVE','REJECTED','SUPERSEDED','INACTIVE')),
    content TEXT NOT NULL,
    snapshot_hash CHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[a-f0-9]{64}$'),
    captured_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    review_comment VARCHAR(500),
    activated_at TIMESTAMPTZ,
    superseded_by UUID REFERENCES cdq_knowledge_version(id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX cdq_knowledge_one_active_idx
    ON cdq_knowledge_version (source_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX cdq_knowledge_one_open_idx
    ON cdq_knowledge_version (source_id)
    WHERE status IN ('PENDING_REVIEW', 'APPROVED');

CREATE FUNCTION enforce_cdq_knowledge_active_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    version_count BIGINT;
    active_count BIGINT;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE status = 'ACTIVE')
    INTO version_count, active_count
    FROM cdq_knowledge_version
    WHERE source_id = 'cdq-fraud-guard';

    IF version_count = 0 AND TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'cdq_knowledge_requires_active',
            MESSAGE = 'cdq_knowledge_requires_active: version history cannot return to empty';
    END IF;
    IF version_count > 0 AND active_count <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            CONSTRAINT = 'cdq_knowledge_requires_active',
            MESSAGE = 'cdq_knowledge_requires_active: version history requires exactly one ACTIVE row';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER cdq_knowledge_requires_active
    AFTER INSERT OR UPDATE OR DELETE ON cdq_knowledge_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_cdq_knowledge_active_version();

CREATE FUNCTION prevent_cdq_knowledge_version_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        CONSTRAINT = 'cdq_knowledge_requires_active',
        MESSAGE = 'cdq_knowledge_requires_active: version history cannot be truncated';
END;
$$;

CREATE TRIGGER cdq_knowledge_prevent_truncate
    BEFORE TRUNCATE ON cdq_knowledge_version
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_cdq_knowledge_version_truncate();

CREATE TABLE cdq_knowledge_scan (
    id UUID PRIMARY KEY,
    scan_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    source_id VARCHAR(64) NOT NULL CHECK (source_id = 'cdq-fraud-guard'),
    scanned_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('UNCHANGED','CHANGES_DETECTED','FAILED')),
    remote_hash CHAR(64) CHECK (remote_hash IS NULL OR remote_hash ~ '^[a-f0-9]{64}$'),
    candidate_version_id UUID REFERENCES cdq_knowledge_version(id),
    failure_code VARCHAR(64) CHECK (failure_code IS NULL OR failure_code IN
        ('SOURCE_UNAVAILABLE','SOURCE_TIMEOUT','SOURCE_RESPONSE_INVALID','SOURCE_CONTENT_INVALID'))
);

CREATE INDEX cdq_knowledge_scan_source_scanned_idx
    ON cdq_knowledge_scan (source_id, scanned_at DESC, scan_sequence DESC);
