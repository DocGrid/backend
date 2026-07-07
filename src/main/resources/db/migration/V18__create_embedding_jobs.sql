-- embedding_jobs: 임베딩 작업 큐 테이블 (EmbeddingJob 엔티티 대응) - Worker 장애 복구의 핵심 테이블
CREATE TABLE embedding_jobs (
    id BIGSERIAL PRIMARY KEY,

    -- 인덱싱 대상 문서 버전 (-> document_versions.id)
    document_version_id BIGINT NOT NULL REFERENCES document_versions (id),

    -- 사용할 임베딩 모델 (-> embedding_models.id)
    embedding_model_id BIGINT NOT NULL REFERENCES embedding_models (id),

    status VARCHAR(20) NOT NULL,
    priority INT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL,

    -- 이 job을 lock한 Worker (-> worker_nodes.id), lock되지 않았으면 NULL
    locked_by_worker_id BIGINT REFERENCES worker_nodes (id),
    locked_at TIMESTAMP,
    lock_expires_at TIMESTAMP,

    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    error_code VARCHAR(100),
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_embedding_jobs_status_priority_created_at ON embedding_jobs (status, priority, created_at);
CREATE INDEX idx_embedding_jobs_lock_expires_at ON embedding_jobs (lock_expires_at);
CREATE INDEX idx_embedding_jobs_document_version_id_embedding_model_id ON embedding_jobs (document_version_id, embedding_model_id);
CREATE INDEX idx_embedding_jobs_locked_by_worker_id ON embedding_jobs (locked_by_worker_id);
