-- embedding_job_attempts: 임베딩 작업 시도 이력 테이블 (EmbeddingJobAttempt 엔티티 대응)
CREATE TABLE embedding_job_attempts (
    id BIGSERIAL PRIMARY KEY,

    -- 이 시도가 속한 임베딩 작업 (-> embedding_jobs.id)
    embedding_job_id BIGINT NOT NULL REFERENCES embedding_jobs (id),

    -- 이 시도를 수행한 Worker (-> worker_nodes.id)
    worker_node_id BIGINT REFERENCES worker_nodes (id),

    attempt_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    duration_ms BIGINT,
    error_code VARCHAR(100),
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_embedding_job_attempts_embedding_job_id_attempt_no
        UNIQUE (embedding_job_id, attempt_no)
);

CREATE INDEX idx_embedding_job_attempts_worker_node_id ON embedding_job_attempts (worker_node_id);
CREATE INDEX idx_embedding_job_attempts_status ON embedding_job_attempts (status);
