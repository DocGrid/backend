-- document_permissions: 문서 단위 예외 권한 테이블 (DocumentPermission 엔티티 대응)
-- CollectionPermission과 동일한 target_type CHECK 제약을 적용한다.
CREATE TABLE document_permissions (
    id BIGSERIAL PRIMARY KEY,

    -- 권한이 적용되는 문서 (-> documents.id)
    document_id BIGINT NOT NULL REFERENCES documents (id),

    target_type VARCHAR(20) NOT NULL,

    -- target_type=USER일 때만 값이 채워짐 (-> users.id)
    user_id BIGINT REFERENCES users (id),

    -- target_type=DEPARTMENT일 때만 값이 채워짐 (-> departments.id)
    department_id BIGINT REFERENCES departments (id),

    -- target_type=ROLE일 때만 값이 채워짐 (-> roles.id)
    role_id BIGINT REFERENCES roles (id),

    permission_type VARCHAR(20) NOT NULL,
    can_read BOOLEAN NOT NULL DEFAULT TRUE,
    can_write BOOLEAN NOT NULL DEFAULT FALSE,
    can_admin BOOLEAN NOT NULL DEFAULT FALSE,

    -- 이 권한을 부여한 사용자 (-> users.id)
    granted_by BIGINT REFERENCES users (id),
    granted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_document_permissions_target_type_fk CHECK (
        (target_type = 'USER' AND user_id IS NOT NULL AND department_id IS NULL AND role_id IS NULL) OR
        (target_type = 'DEPARTMENT' AND department_id IS NOT NULL AND user_id IS NULL AND role_id IS NULL) OR
        (target_type = 'ROLE' AND role_id IS NOT NULL AND user_id IS NULL AND department_id IS NULL)
    )
);

CREATE INDEX idx_document_permissions_document_id ON document_permissions (document_id);
CREATE INDEX idx_document_permissions_target_type_user_id ON document_permissions (target_type, user_id);
CREATE INDEX idx_document_permissions_target_type_department_id ON document_permissions (target_type, department_id);
CREATE INDEX idx_document_permissions_target_type_role_id ON document_permissions (target_type, role_id);
CREATE INDEX idx_document_permissions_expires_at ON document_permissions (expires_at);
