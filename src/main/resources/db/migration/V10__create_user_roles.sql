-- user_roles: 사용자-역할 매핑 테이블 (UserRole 엔티티 대응, users-roles N:M 해소)
CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,

    -- 역할이 부여된 사용자 (-> users.id)
    user_id BIGINT NOT NULL REFERENCES users (id),

    -- 부여된 역할 (-> roles.id)
    role_id BIGINT NOT NULL REFERENCES roles (id),

    -- 이 역할을 부여한 사용자(관리자 등) (-> users.id)
    assigned_by BIGINT REFERENCES users (id),

    assigned_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_roles_user_id_role_id UNIQUE (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);
