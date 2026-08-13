-- departments: 조직 계층 구조(부서 트리) 테이블 (Department 엔티티 대응)
CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,

    -- 상위 부서 self-FK, 최상위 부서는 NULL
    parent_department_id BIGINT REFERENCES departments (id),

    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_departments_code UNIQUE (code)
);

CREATE INDEX idx_departments_parent_department_id ON departments (parent_department_id);
CREATE INDEX idx_departments_status ON departments (status);
