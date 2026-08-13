-- roles: 역할(권한 그룹) 테이블 (Role 엔티티 대응)
CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,

    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_roles_code UNIQUE (code)
);
