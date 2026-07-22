-- Worker의 Job Claim 1회를 식별하는 UUID Token을 저장한다.
ALTER TABLE embedding_jobs
    ADD COLUMN claim_token VARCHAR(36);
