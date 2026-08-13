-- document_chunks: 문서 청크(분할 조각) 테이블 (DocumentChunk 엔티티 대응)
-- 주의: status 컬럼이 없다 - 한번 생성되면 변경되지 않는 불변 데이터다.
CREATE TABLE document_chunks (
    id BIGSERIAL PRIMARY KEY,

    -- 이 청크가 속한 문서 버전 (-> document_versions.id)
    document_version_id BIGINT NOT NULL REFERENCES document_versions (id),

    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    token_count INT NOT NULL,
    char_start INT NOT NULL,
    char_end INT NOT NULL,
    page_no INT,
    section_title VARCHAR(500),
    content_hash VARCHAR(128),

    -- JSON 컬럼 임시 매핑 - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    metadata_json TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_document_chunks_document_version_id_chunk_index
        UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX idx_document_chunks_document_version_id ON document_chunks (document_version_id);
CREATE INDEX idx_document_chunks_page_no ON document_chunks (page_no);
CREATE INDEX idx_document_chunks_content_hash ON document_chunks (content_hash);
