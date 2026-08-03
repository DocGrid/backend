-- Retry 예약 Job이 실행 가능해지는 시각. null인 기존·신규 Job은 즉시 Claim할 수 있다.
ALTER TABLE embedding_jobs
    ADD COLUMN next_retry_at TIMESTAMP;

CREATE INDEX idx_embedding_jobs_status_next_retry_at
    ON embedding_jobs (status, next_retry_at);
