-- users: 사용자 테이블 (User 엔티티 대응)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,

    -- 소속 부서, 미배정일 수 있으므로 nullable (-> departments.id)
    department_id BIGINT REFERENCES departments (id),

    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    nickname VARCHAR(100),
    profile_image_url VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    last_login_at TIMESTAMP,
    deleted_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_department_id ON users (department_id);
CREATE INDEX idx_users_status ON users (status);
