-- failover_events: OpenSQL HA(Failover) 이벤트 로그 테이블 (FailoverEvent 엔티티 대응)
-- 다른 도메인 테이블을 참조하지 않는 독립적인 로그 테이블이다.
CREATE TABLE failover_events (
    id BIGSERIAL PRIMARY KEY,

    event_type VARCHAR(30) NOT NULL,
    source_node VARCHAR(255),
    target_node VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    message TEXT,

    -- JSON 컬럼 임시 매핑 - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    metadata_json TEXT,

    occurred_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_failover_events_event_type ON failover_events (event_type);
CREATE INDEX idx_failover_events_status ON failover_events (status);
CREATE INDEX idx_failover_events_occurred_at ON failover_events (occurred_at);
