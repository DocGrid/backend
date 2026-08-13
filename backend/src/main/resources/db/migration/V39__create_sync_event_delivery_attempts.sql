-- sync_event_delivery_attempts: 완료 후에도 보존되는 Dispatcher Claim 세대별 처리·복구 이력
CREATE TABLE sync_event_delivery_attempts (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES sync_outbox_events (event_id) ON DELETE CASCADE,
    claim_token UUID NOT NULL,
    attempt_no INT NOT NULL,
    dispatcher_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_sync_event_delivery_attempts_claim_token UNIQUE (claim_token),
    CONSTRAINT uk_sync_event_delivery_attempts_event_attempt UNIQUE (event_id, attempt_no)
);

CREATE INDEX idx_sync_event_delivery_attempts_event_started
    ON sync_event_delivery_attempts (event_id, started_at DESC);
CREATE INDEX idx_sync_event_delivery_attempts_status_started
    ON sync_event_delivery_attempts (status, started_at);
