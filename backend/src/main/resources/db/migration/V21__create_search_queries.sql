-- search_queries: 검색 요청 루트 테이블 (SearchQuery 엔티티 대응)
-- TODO: query_vector는 추후 OpenSQL vector 타입으로 교체 필요, 현재는 TEXT 임시 매핑.
CREATE TABLE search_queries (
    id BIGSERIAL PRIMARY KEY,

    -- 검색을 요청한 사용자 (-> users.id)
    user_id BIGINT NOT NULL REFERENCES users (id),

    -- 검색 범위를 특정 컬렉션으로 좁힌 경우에만 값이 있음 (-> collections.id)
    collection_id BIGINT REFERENCES collections (id),

    query_text TEXT NOT NULL,

    -- 질의를 벡터화할 때 사용한 임베딩 모델 (-> embedding_models.id)
    query_embedding_model_id BIGINT REFERENCES embedding_models (id),

    -- TODO: 추후 OpenSQL vector 타입으로 교체 필요. 현재는 TEXT 임시 매핑.
    query_vector TEXT,

    search_type VARCHAR(20) NOT NULL,
    top_k INT NOT NULL,

    -- JSON 컬럼 임시 매핑 - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    filters_json TEXT,

    latency_ms INT,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_search_queries_user_id ON search_queries (user_id);
CREATE INDEX idx_search_queries_collection_id ON search_queries (collection_id);
CREATE INDEX idx_search_queries_query_embedding_model_id ON search_queries (query_embedding_model_id);
CREATE INDEX idx_search_queries_search_type ON search_queries (search_type);
CREATE INDEX idx_search_queries_created_at ON search_queries (created_at);
