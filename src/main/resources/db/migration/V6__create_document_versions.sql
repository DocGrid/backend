-- document_versions: 문서 버전(스냅샷) 테이블 (DocumentVersion 엔티티 대응)
CREATE TABLE document_versions (
    id BIGSERIAL PRIMARY KEY,

    -- 이 버전이 속한 문서 (-> documents.id)
    document_id BIGINT NOT NULL REFERENCES documents (id),

    -- 이 버전이 사용하는 실제 파일 (-> file_objects.id)
    file_object_id BIGINT REFERENCES file_objects (id),

    version_no INT NOT NULL,
    title_snapshot VARCHAR(500),
    content_hash VARCHAR(128),
    file_hash VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    indexed_at TIMESTAMP,

    -- 이 버전을 생성한 사용자 (-> users.id)
    created_by BIGINT REFERENCES users (id),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_document_versions_document_id_version_no UNIQUE (document_id, version_no)
);

CREATE INDEX idx_document_versions_document_id ON document_versions (document_id);
CREATE INDEX idx_document_versions_file_object_id ON document_versions (file_object_id);
CREATE INDEX idx_document_versions_status ON document_versions (status);

-- documents <-> document_versions 순환 FK 마무리:
-- document_versions 테이블이 이제 존재하므로 documents.current_version_id에 대한 FK 제약을 추가한다.
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version_id
    FOREIGN KEY (current_version_id) REFERENCES document_versions (id);
