-- user_document_access_cache: 사용자-문서 접근 권한 캐시 테이블 (UserDocumentAccessCache 엔티티 대응)
-- 주의: 권한의 source of truth가 아니라 OWNER/직접 USER 권한만 저장하는 검색 pre-filter 가속 캐시다.
-- PUBLIC/ROLE/DEPARTMENT 권한은 저장하지 않으며, 최종 응답 전에는 반드시 live permission check가 필요하다.
CREATE TABLE user_document_access_cache (
    id BIGSERIAL PRIMARY KEY,

    -- 접근 권한을 캐시하는 대상 사용자 (-> users.id)
    user_id BIGINT NOT NULL REFERENCES users (id),

    -- 접근 대상 문서 (-> documents.id)
    document_id BIGINT NOT NULL REFERENCES documents (id),

    can_read BOOLEAN NOT NULL,
    can_write BOOLEAN NOT NULL,
    can_admin BOOLEAN NOT NULL,

    -- OWNER / DIRECT_DOCUMENT_PERMISSION / DIRECT_COLLECTION_PERMISSION
    source_type VARCHAR(30) NOT NULL,

    -- 파생 근거가 된 권한 레코드 id(document_permissions.id 또는 collection_permissions.id), OWNER면 NULL
    source_id BIGINT,

    computed_at TIMESTAMP NOT NULL,
    invalidated_at TIMESTAMP,
    expires_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_document_access_cache_user_id_document_id_source_type_source_id
        UNIQUE (user_id, document_id, source_type, source_id)
);

CREATE INDEX idx_user_document_access_cache_user_id_document_id ON user_document_access_cache (user_id, document_id);
CREATE INDEX idx_user_document_access_cache_user_id_can_read ON user_document_access_cache (user_id, can_read);
CREATE INDEX idx_user_document_access_cache_document_id ON user_document_access_cache (document_id);
CREATE INDEX idx_user_document_access_cache_invalidated_at ON user_document_access_cache (invalidated_at);
CREATE INDEX idx_user_document_access_cache_expires_at ON user_document_access_cache (expires_at);
