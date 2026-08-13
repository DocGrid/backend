-- documents: 문서(논리적 루트) 테이블 (Document 엔티티 대응)
--
-- 주의: documents <-> document_versions는 순환 FK 구조다.
-- 이 마이그레이션 시점에는 document_versions 테이블이 아직 없으므로 current_version_id는
-- FK 없이 컬럼만 생성하고, V6__create_document_versions.sql에서 document_versions 테이블을 만든 뒤
-- ALTER TABLE로 FK 제약을 추가한다.
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,

    -- 문서 소유자 (-> users.id)
    owner_user_id BIGINT NOT NULL REFERENCES users (id),

    -- 현재 활성 버전 (-> document_versions.id, FK는 V6에서 추가 예정)
    current_version_id BIGINT,

    title VARCHAR(500) NOT NULL,
    description TEXT,
    document_type VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_documents_owner_user_id ON documents (owner_user_id);
CREATE INDEX idx_documents_current_version_id ON documents (current_version_id);
CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_documents_visibility ON documents (visibility);
CREATE INDEX idx_documents_status_visibility ON documents (status, visibility);
