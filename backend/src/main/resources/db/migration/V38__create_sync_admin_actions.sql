-- Reconciler가 판정한 자동 복구 가능 여부를 관리자 제어 경계에서도 재사용한다.
ALTER TABLE sync_consistency_issues
    ADD COLUMN repairable BOOLEAN NOT NULL DEFAULT FALSE;

-- sync_admin_actions: 운영자가 실행한 Sync 상태 변경을 삭제 불가능한 감사 이력으로 보존한다.
CREATE TABLE sync_admin_actions (
    id BIGSERIAL PRIMARY KEY,
    action_id UUID NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    admin_user_id BIGINT NOT NULL REFERENCES users (id),
    reason TEXT,
    metadata_json TEXT,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_sync_admin_actions_action_id UNIQUE (action_id)
);

CREATE INDEX idx_sync_admin_actions_admin_occurred
    ON sync_admin_actions (admin_user_id, occurred_at DESC, id DESC);
CREATE INDEX idx_sync_admin_actions_type_occurred
    ON sync_admin_actions (action_type, occurred_at DESC, id DESC);
CREATE INDEX idx_sync_admin_actions_target
    ON sync_admin_actions (target_type, target_id, occurred_at DESC);
