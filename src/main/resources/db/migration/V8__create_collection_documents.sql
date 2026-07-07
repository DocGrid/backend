-- collection_documents: collections와 documents의 N:M 관계를 해소하는 중간 테이블 (CollectionDocument 엔티티 대응)
CREATE TABLE collection_documents (
    id BIGSERIAL PRIMARY KEY,

    -- 문서가 속하는 컬렉션 (-> collections.id)
    collection_id BIGINT NOT NULL REFERENCES collections (id),

    -- 컬렉션에 속하는 문서 (-> documents.id)
    document_id BIGINT NOT NULL REFERENCES documents (id),

    -- 이 문서를 컬렉션에 추가한 사용자 (-> users.id)
    added_by BIGINT REFERENCES users (id),

    added_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_collection_documents_collection_id_document_id UNIQUE (collection_id, document_id)
);

CREATE INDEX idx_collection_documents_collection_id ON collection_documents (collection_id);
CREATE INDEX idx_collection_documents_document_id ON collection_documents (document_id);
