-- response_citations: 응답 출처(citation) 테이블 (ResponseCitation 엔티티 대응) - 출처 기반 RAG의 핵심 테이블
CREATE TABLE response_citations (
    id BIGSERIAL PRIMARY KEY,

    -- 이 출처가 속한 최종 답변 (-> rag_responses.id)
    response_id BIGINT NOT NULL REFERENCES rag_responses (id),

    -- 근거가 된 chunk (-> document_chunks.id)
    chunk_id BIGINT NOT NULL REFERENCES document_chunks (id),

    -- 해당 chunk가 검색 후보였을 때의 결과 레코드 (-> search_results.id)
    search_result_id BIGINT REFERENCES search_results (id),

    citation_order INT NOT NULL,
    citation_label VARCHAR(20),
    quoted_text TEXT,
    page_no INT,
    relevance_score NUMERIC(10, 6),

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_response_citations_response_id_chunk_id UNIQUE (response_id, chunk_id)
);

CREATE INDEX idx_response_citations_response_id ON response_citations (response_id);
CREATE INDEX idx_response_citations_chunk_id ON response_citations (chunk_id);
CREATE INDEX idx_response_citations_search_result_id ON response_citations (search_result_id);
