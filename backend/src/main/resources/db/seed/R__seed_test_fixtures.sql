-- 개발용 테스트 픽스처: documents → document_versions → document_chunks → embeddings
-- 벡터 검색 동작 확인용 더미 데이터 (PUBLIC 문서 2개, 청크 4개, 임베딩 4개)
-- ON CONFLICT: 재실행 시 중복 삽입을 방지한다.

-- 1. 문서 2개 (PUBLIC, INDEXED)
INSERT INTO documents (owner_user_id, title, description, document_type, source_type, status, visibility, created_at, updated_at)
SELECT id, 'Spring Boot 개발 가이드', 'Spring Boot 핵심 개념 및 사용법 정리', 'TXT', 'UPLOAD', 'INDEXED', 'PUBLIC', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users WHERE email = 'kcw130502@gmail.com'
ON CONFLICT DO NOTHING;

INSERT INTO documents (owner_user_id, title, description, document_type, source_type, status, visibility, created_at, updated_at)
SELECT id, 'Python 데이터 분석 입문', 'pandas, numpy 활용 데이터 분석 가이드', 'TXT', 'UPLOAD', 'INDEXED', 'PUBLIC', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users WHERE email = 'kcw130502@gmail.com'
ON CONFLICT DO NOTHING;

-- 2. 문서 버전 (각 문서당 1개)
INSERT INTO document_versions (document_id, version_no, title_snapshot, status, created_by, created_at, updated_at)
SELECT d.id, 1, d.title, 'INDEXED', u.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM documents d, users u
WHERE d.title IN ('Spring Boot 개발 가이드', 'Python 데이터 분석 입문')
  AND u.email = 'kcw130502@gmail.com'
  AND NOT EXISTS (
      SELECT 1 FROM document_versions dv WHERE dv.document_id = d.id AND dv.version_no = 1
  );

-- 3. current_version_id 업데이트
UPDATE documents d
SET current_version_id = dv.id
FROM document_versions dv
WHERE dv.document_id = d.id
  AND d.title IN ('Spring Boot 개발 가이드', 'Python 데이터 분석 입문')
  AND d.current_version_id IS NULL;

-- 4. 청크 (문서당 2개)
INSERT INTO document_chunks (document_version_id, chunk_index, chunk_text, token_count, char_start, char_end, created_at, updated_at)
SELECT dv.id, 0,
    'Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.',
    32, 0, 80, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_versions dv JOIN documents d ON dv.document_id = d.id
WHERE d.title = 'Spring Boot 개발 가이드'
ON CONFLICT (document_version_id, chunk_index) DO NOTHING;

INSERT INTO document_chunks (document_version_id, chunk_index, chunk_text, token_count, char_start, char_end, created_at, updated_at)
SELECT dv.id, 1,
    'Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.',
    30, 81, 165, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_versions dv JOIN documents d ON dv.document_id = d.id
WHERE d.title = 'Spring Boot 개발 가이드'
ON CONFLICT (document_version_id, chunk_index) DO NOTHING;

INSERT INTO document_chunks (document_version_id, chunk_index, chunk_text, token_count, char_start, char_end, created_at, updated_at)
SELECT dv.id, 0,
    'Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.',
    30, 0, 82, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_versions dv JOIN documents d ON dv.document_id = d.id
WHERE d.title = 'Python 데이터 분석 입문'
ON CONFLICT (document_version_id, chunk_index) DO NOTHING;

INSERT INTO document_chunks (document_version_id, chunk_index, chunk_text, token_count, char_start, char_end, created_at, updated_at)
SELECT dv.id, 1,
    'numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다.',
    28, 83, 158, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_versions dv JOIN documents d ON dv.document_id = d.id
WHERE d.title = 'Python 데이터 분석 입문'
ON CONFLICT (document_version_id, chunk_index) DO NOTHING;

-- 5. 임베딩 (청크당 1개, 개발용 임의 벡터)
INSERT INTO embeddings (chunk_id, document_id, document_version_id, embedding_model_id, vector, dimension, status, created_at, updated_at)
SELECT dc.id, d.id, dv.id, em.id,
    ('[' || (SELECT string_agg(CASE WHEN n BETWEEN 1 AND 10 THEN '0.3' ELSE '0.001' END, ',') FROM generate_series(1, 1024) n) || ']')::vector(1024),
    1024, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_chunks dc
JOIN document_versions dv ON dc.document_version_id = dv.id
JOIN documents d ON dv.document_id = d.id
JOIN embedding_models em ON em.model_name = 'BAAI/bge-m3'
WHERE d.title = 'Spring Boot 개발 가이드' AND dc.chunk_index = 0
ON CONFLICT (chunk_id, embedding_model_id) DO NOTHING;

INSERT INTO embeddings (chunk_id, document_id, document_version_id, embedding_model_id, vector, dimension, status, created_at, updated_at)
SELECT dc.id, d.id, dv.id, em.id,
    ('[' || (SELECT string_agg(CASE WHEN n BETWEEN 11 AND 20 THEN '0.3' ELSE '0.001' END, ',') FROM generate_series(1, 1024) n) || ']')::vector(1024),
    1024, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_chunks dc
JOIN document_versions dv ON dc.document_version_id = dv.id
JOIN documents d ON dv.document_id = d.id
JOIN embedding_models em ON em.model_name = 'BAAI/bge-m3'
WHERE d.title = 'Spring Boot 개발 가이드' AND dc.chunk_index = 1
ON CONFLICT (chunk_id, embedding_model_id) DO NOTHING;

INSERT INTO embeddings (chunk_id, document_id, document_version_id, embedding_model_id, vector, dimension, status, created_at, updated_at)
SELECT dc.id, d.id, dv.id, em.id,
    ('[' || (SELECT string_agg(CASE WHEN n BETWEEN 21 AND 30 THEN '0.3' ELSE '0.001' END, ',') FROM generate_series(1, 1024) n) || ']')::vector(1024),
    1024, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_chunks dc
JOIN document_versions dv ON dc.document_version_id = dv.id
JOIN documents d ON dv.document_id = d.id
JOIN embedding_models em ON em.model_name = 'BAAI/bge-m3'
WHERE d.title = 'Python 데이터 분석 입문' AND dc.chunk_index = 0
ON CONFLICT (chunk_id, embedding_model_id) DO NOTHING;

INSERT INTO embeddings (chunk_id, document_id, document_version_id, embedding_model_id, vector, dimension, status, created_at, updated_at)
SELECT dc.id, d.id, dv.id, em.id,
    ('[' || (SELECT string_agg(CASE WHEN n BETWEEN 31 AND 40 THEN '0.3' ELSE '0.001' END, ',') FROM generate_series(1, 1024) n) || ']')::vector(1024),
    1024, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM document_chunks dc
JOIN document_versions dv ON dc.document_version_id = dv.id
JOIN documents d ON dv.document_id = d.id
JOIN embedding_models em ON em.model_name = 'BAAI/bge-m3'
WHERE d.title = 'Python 데이터 분석 입문' AND dc.chunk_index = 1
ON CONFLICT (chunk_id, embedding_model_id) DO NOTHING;
