-- worker_nodes: 인덱싱 Worker 노드 상태 테이블 (WorkerNode 엔티티 대응)
CREATE TABLE worker_nodes (
    id BIGSERIAL PRIMARY KEY,

    worker_name VARCHAR(200) NOT NULL,
    host_name VARCHAR(255),
    ip_address VARCHAR(45),
    status VARCHAR(20) NOT NULL,
    last_heartbeat_at TIMESTAMP,
    started_at TIMESTAMP NOT NULL,
    stopped_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_worker_nodes_status ON worker_nodes (status);
CREATE INDEX idx_worker_nodes_last_heartbeat_at ON worker_nodes (last_heartbeat_at);
