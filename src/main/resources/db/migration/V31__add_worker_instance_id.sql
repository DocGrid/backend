-- Worker 프로세스의 실행 단위를 식별한다. worker_name은 역할명이므로 중복을 허용한다.
ALTER TABLE worker_nodes
    ADD COLUMN instance_id VARCHAR(64);

-- 기존 환경에 Worker 데이터가 있어도 NOT NULL 제약을 안전하게 적용한다.
UPDATE worker_nodes
SET instance_id = CONCAT('legacy-', id)
WHERE instance_id IS NULL;

ALTER TABLE worker_nodes
    ALTER COLUMN instance_id SET NOT NULL;

ALTER TABLE worker_nodes
    ADD CONSTRAINT uk_worker_nodes_instance_id UNIQUE (instance_id);

ALTER TABLE worker_nodes
    ADD CONSTRAINT ck_worker_nodes_status
    CHECK (status IN ('ACTIVE', 'IDLE', 'DEAD', 'STOPPED'));
