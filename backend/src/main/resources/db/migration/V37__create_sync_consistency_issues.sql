-- sync_consistency_issues: Reconciler가 발견한 동일 불일치를 하나의 생명주기로 관리한다.
CREATE TABLE sync_consistency_issues (
    id BIGSERIAL PRIMARY KEY,
    issue_key VARCHAR(255) NOT NULL,
    issue_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    document_id BIGINT REFERENCES documents (id),
    document_version_id BIGINT REFERENCES document_versions (id),
    embedding_model_id BIGINT REFERENCES embedding_models (id),
    expected_json TEXT,
    actual_json TEXT,
    detected_at TIMESTAMP NOT NULL,
    last_detected_at TIMESTAMP NOT NULL,
    repair_event_id UUID REFERENCES sync_outbox_events (event_id),
    repair_attempt_count INT NOT NULL DEFAULT 0,
    resolved_at TIMESTAMP,
    resolution_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_sync_consistency_issues_issue_key UNIQUE (issue_key)
);

CREATE INDEX idx_sync_consistency_issues_status_last_detected
    ON sync_consistency_issues (status, last_detected_at DESC, id DESC);
CREATE INDEX idx_sync_consistency_issues_document_version
    ON sync_consistency_issues (document_version_id, status);
CREATE INDEX idx_sync_consistency_issues_repair_event
    ON sync_consistency_issues (repair_event_id);

-- sync_reconciliation_runs: Batch 실행의 범위와 탐지·복구 결과를 운영 지표로 보존한다.
CREATE TABLE sync_reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL,
    mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_cursor BIGINT NOT NULL,
    end_cursor BIGINT NOT NULL,
    scanned_count INT NOT NULL,
    detected_count INT NOT NULL,
    repair_requested_count INT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    error_code VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_sync_reconciliation_runs_run_id UNIQUE (run_id)
);

CREATE INDEX idx_sync_reconciliation_runs_started_at
    ON sync_reconciliation_runs (started_at DESC, id DESC);
CREATE INDEX idx_sync_reconciliation_runs_status
    ON sync_reconciliation_runs (status, started_at DESC);
