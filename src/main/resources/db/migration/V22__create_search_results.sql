-- search_results: 검색 결과(후보) 테이블 (SearchResult 엔티티 대응) - LLM 최종 답변이 아니라 검색 후보를 저장
CREATE TABLE search_results (
    id BIGSERIAL PRIMARY KEY,

    -- 이 결과가 속한 검색 요청 (-> search_queries.id)
    query_id BIGINT NOT NULL REFERENCES search_queries (id),

    -- 검색된 chunk (-> document_chunks.id)
    chunk_id BIGINT NOT NULL REFERENCES document_chunks (id),

    -- 매칭에 사용된 임베딩 (-> embeddings.id)
    embedding_id BIGINT REFERENCES embeddings (id),

    rank_no INT NOT NULL,
    similarity_score NUMERIC(10, 6) NOT NULL,
    keyword_score NUMERIC(10, 6),
    final_score NUMERIC(10, 6) NOT NULL,
    matched_text TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_search_results_query_id_chunk_id UNIQUE (query_id, chunk_id)
);

CREATE INDEX idx_search_results_query_id ON search_results (query_id);
CREATE INDEX idx_search_results_chunk_id ON search_results (chunk_id);
CREATE INDEX idx_search_results_embedding_id ON search_results (embedding_id);
CREATE INDEX idx_search_results_rank_no ON search_results (rank_no);
