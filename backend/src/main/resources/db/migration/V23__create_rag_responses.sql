-- rag_responses: RAG 최종 답변 테이블 (RagResponse 엔티티 대응) - 사용자가 실제로 보는 최종 답변
CREATE TABLE rag_responses (
    id BIGSERIAL PRIMARY KEY,

    -- 이 답변이 근거로 하는 검색 요청 (-> search_queries.id)
    query_id BIGINT NOT NULL REFERENCES search_queries (id),

    answer_text TEXT NOT NULL,
    llm_provider VARCHAR(50),
    llm_model_name VARCHAR(100),
    prompt_text TEXT,
    input_token_count INT,
    output_token_count INT,
    latency_ms INT,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rag_responses_query_id ON rag_responses (query_id);
CREATE INDEX idx_rag_responses_status ON rag_responses (status);
CREATE INDEX idx_rag_responses_created_at ON rag_responses (created_at);
