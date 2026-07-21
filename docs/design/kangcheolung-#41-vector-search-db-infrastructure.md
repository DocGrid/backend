# #41 벡터 검색 DB 인프라 구축

closes #41

## 배경

벡터 검색 기능 구현을 위해 DB 스키마와 Java 엔티티를 pgvector에 맞게 정비한다.
기존에 `embeddings.vector`, `search_queries.query_vector` 컬럼이 임시로 `TEXT` 타입으로 매핑되어 있었고,
Hibernate 스키마 검증(`ddl-auto=validate`)이 실패하는 상태였다.

## 작업 내용

### 1. Flyway V32 — vector 컬럼 타입 변환 및 HNSW 인덱스 추가

```sql
ALTER TABLE embeddings DROP COLUMN vector;
ALTER TABLE embeddings ADD COLUMN vector vector(1024) NOT NULL;
ALTER TABLE search_queries ALTER COLUMN query_vector TYPE vector(1024) USING query_vector::vector;
CREATE INDEX ON embeddings USING hnsw (vector vector_cosine_ops);
```

- `embeddings.vector`: TEXT → vector(1024)
- `search_queries.query_vector`: TEXT → vector(1024)
- HNSW 인덱스: 코사인 거리 기반 ANN 검색 가속

### 2. db/seed 이관

기존 `R__seed_mock_embedding_model.sql`(MOCK 모델)을 제거하고, 실제 BAAI/bge-m3 모델 seed로 교체했다.

- `R__seed_bge_m3_embedding_model.sql`: HUGGINGFACE/BAAI/bge-m3(1024차원, COSINE) 활성 모델 등록. ON CONFLICT upsert.
- `R__seed_test_fixtures.sql`: PUBLIC 문서 2개, 청크 4개, 임베딩 4개(개발용 더미 벡터). ON CONFLICT DO NOTHING으로 멱등 처리.

### 3. VectorType — 커스텀 Hibernate UserType 구현

`global/common/type/VectorType.java`

- pgvector의 `vector(1024)` SQL 타입(Types#OTHER)을 Java `float[]`로 매핑
- PostgreSQL JDBC의 `PGobject`로 write, `getString` + 파싱으로 read
- Hibernate 스키마 검증 통과: DB의 `Types#OTHER`와 UserType의 `getSqlType() = Types.OTHER`가 일치

### 4. 엔티티 수정

| 엔티티 | 변경 전 | 변경 후 |
|--------|---------|---------|
| `Embedding.vector` | `String` / `columnDefinition="TEXT"` | `float[]` / `@Type(VectorType.class)` / `columnDefinition="vector(1024)"` |
| `SearchQuery.queryVector` | `String` / `columnDefinition="TEXT"` | `float[]` / `@Type(VectorType.class)` / `columnDefinition="vector(1024)"` |

### 5. build.gradle

`runtimeOnly 'org.postgresql:postgresql'` → `implementation`

PGobject를 컴파일 타임에 사용하기 위해 스코프 변경.

## 설계 결정

- **seed를 db/migration이 아닌 db/seed에 배치**: 데이터 seed는 스키마 변경이 아니므로 분리. `R__`(repeatable) 방식으로 idempotent하게 관리.
- **VectorType 직접 구현**: 외부 라이브러리(pgvector-java, hypersistence-utils) 없이 JDBC 드라이버만으로 처리. 의존성 최소화.
- **float[] 선택**: 검색 시 임베딩 서버 응답(`List<Float>`)을 직접 담을 수 있고, pgvector 연산과 자연스럽게 연결됨.
