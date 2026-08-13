-- indexing_events: 인덱싱 상태 변경 이벤트 로그 테이블 (IndexingEvent 엔티티 대응, append-only)
CREATE TABLE indexing_events (
    id BIGSERIAL PRIMARY KEY,

    -- 이 이벤트가 발생한 임베딩 작업 (-> embedding_jobs.id)
    embedding_job_id BIGINT NOT NULL REFERENCES embedding_jobs (id),

    event_type VARCHAR(30) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30),
    message TEXT,

    -- JSON 컬럼 임시 매핑 - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    metadata_json TEXT,

    occurred_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_indexing_events_embedding_job_id ON indexing_events (embedding_job_id);
CREATE INDEX idx_indexing_events_event_type ON indexing_events (event_type);
CREATE INDEX idx_indexing_events_occurred_at ON indexing_events (occurred_at);
