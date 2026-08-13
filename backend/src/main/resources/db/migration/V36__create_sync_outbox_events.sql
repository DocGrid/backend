-- sync_outbox_events: 도메인 변경을 후속 동기화 처리로 안전하게 전달하는 Transactional Outbox
CREATE TABLE sync_outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    aggregate_version BIGINT,
    event_type VARCHAR(50) NOT NULL,
    payload_json TEXT,
    status VARCHAR(20) NOT NULL,
    available_at TIMESTAMP NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL,
    claim_token UUID,
    locked_by VARCHAR(200),
    lock_expires_at TIMESTAMP,
    last_error_code VARCHAR(100),
    last_error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_sync_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT uk_sync_outbox_events_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_sync_outbox_events_dispatch
    ON sync_outbox_events (status, available_at, occurred_at, id);
CREATE INDEX idx_sync_outbox_events_lock_expires_at
    ON sync_outbox_events (lock_expires_at);
CREATE INDEX idx_sync_outbox_events_aggregate
    ON sync_outbox_events (aggregate_type, aggregate_id, aggregate_version);

-- 최초 Job과 원인이 된 Event를 연결해 Event 재전달에서도 Job을 한 번만 만들 수 있게 한다.
ALTER TABLE embedding_jobs
    ADD COLUMN source_event_id UUID,
    ADD CONSTRAINT fk_embedding_jobs_source_event
        FOREIGN KEY (source_event_id) REFERENCES sync_outbox_events (event_id),
    ADD CONSTRAINT uk_embedding_jobs_source_event_id UNIQUE (source_event_id);
