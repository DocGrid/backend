-- embeddings.vector: TEXT NOT NULL → vector(1024) NOT NULL
-- 개발 환경 기준 실 데이터 없음을 전제로 drop/add 방식 사용
ALTER TABLE embeddings DROP COLUMN vector;
ALTER TABLE embeddings ADD COLUMN vector vector(1024) NOT NULL;

-- search_queries.query_vector: TEXT → vector(1024) (nullable 유지)
ALTER TABLE search_queries ALTER COLUMN query_vector TYPE vector(1024) USING query_vector::vector;

-- HNSW 인덱스: 코사인 거리 기반 ANN 검색
CREATE INDEX ON embeddings USING hnsw (vector vector_cosine_ops);
