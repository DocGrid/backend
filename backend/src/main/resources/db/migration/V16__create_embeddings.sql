-- embeddings: 임베딩(벡터) 테이블 (Embedding 엔티티 대응) - OpenSQL vector search의 핵심 테이블
-- 주의: document_id/document_version_id는 검색 성능용 역정규화 필드로, 각각
-- chunk.documentVersion.document / chunk.documentVersion 값과 항상 일치해야 한다.
-- TODO: vector 컬럼은 추후 OpenSQL vector 타입(예: vector(768/1024/1536))으로 교체 필요, 현재는 TEXT 임시 매핑.
CREATE TABLE embeddings (
    id BIGSERIAL PRIMARY KEY,

    -- 이 임베딩의 원본 chunk (-> document_chunks.id)
    chunk_id BIGINT NOT NULL REFERENCES document_chunks (id),

    -- 검색 성능용 역정규화 필드 (-> documents.id)
    document_id BIGINT NOT NULL REFERENCES documents (id),

    -- 검색 성능용 역정규화 필드 (-> document_versions.id)
    document_version_id BIGINT NOT NULL REFERENCES document_versions (id),

    -- 이 벡터를 생성한 임베딩 모델 (-> embedding_models.id)
    embedding_model_id BIGINT NOT NULL REFERENCES embedding_models (id),

    -- TODO: 추후 OpenSQL vector 타입으로 교체 필요. 현재는 TEXT 임시 매핑.
    vector TEXT NOT NULL,
    dimension INT NOT NULL,
    vector_hash VARCHAR(128),
    status VARCHAR(20) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_embeddings_chunk_id_embedding_model_id UNIQUE (chunk_id, embedding_model_id)
);

CREATE INDEX idx_embeddings_embedding_model_id_status ON embeddings (embedding_model_id, status);
CREATE INDEX idx_embeddings_document_id ON embeddings (document_id);
CREATE INDEX idx_embeddings_document_version_id ON embeddings (document_version_id);
