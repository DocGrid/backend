ALTER TABLE embedding_job_attempts
    ADD COLUMN claim_token VARCHAR(36);

ALTER TABLE embedding_job_attempts
    ADD CONSTRAINT uk_embedding_job_attempts_embedding_job_id_claim_token
        UNIQUE (embedding_job_id, claim_token);
