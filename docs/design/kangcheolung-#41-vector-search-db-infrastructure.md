# #41 벡터 검색 DB 인프라 구축

closes #41

---

## 배경

벡터 검색 기능 구현을 위해 DB 스키마와 Java 엔티티를 pgvector에 맞게 정비한다. 기존에 `embeddings.vector`, `search_queries.query_vector` 컬럼이 임시로 `TEXT` 타입으로 매핑되어 있었고, Hibernate 스키마 검증(`ddl-auto=validate`)이 실패하는 상태였다.

이 이슈에서 다루는 범위:
- Flyway V32 마이그레이션: `TEXT` → `vector(1024)` 타입 변환 + HNSW 인덱스 생성
- `VectorType.java`: pgvector ↔ Java `float[]` 변환을 위한 커스텀 Hibernate UserType
- 엔티티 수정: `Embedding.vector`, `SearchQuery.queryVector`
- `build.gradle` 스코프 변경
- db/seed 정비: MOCK 모델 제거 → bge-m3 seed로 교체

---

## 1. Flyway V32 — vector 컬럼 타입 변환 + HNSW 인덱스

```sql
ALTER TABLE embeddings DROP COLUMN vector;
ALTER TABLE embeddings ADD COLUMN vector vector(1024) NOT NULL;

ALTER TABLE search_queries ALTER COLUMN query_vector TYPE vector(1024)
    USING query_vector::vector;

CREATE INDEX ON embeddings USING hnsw (vector vector_cosine_ops);
```

결정 사항:
- `embeddings.vector`는 개발 환경에 실 데이터 없음을 전제로 DROP/ADD 방식 사용. (`ALTER COLUMN TYPE USING`은 기존 값이 vector로 캐스팅 가능해야 하는데, TEXT 상태로는 불가)
- `search_queries.query_vector`는 nullable이라 USING 캐스팅으로 안전하게 타입만 변환.
- HNSW 인덱스: IVFFlat 대비 구축 속도 빠르고 실시간 insert에 강해서 선택. `vector_cosine_ops`는 코사인 거리(`<=>`) 연산에 최적화된 인덱스 옵션.

---

## 2. VectorType.java — 커스텀 Hibernate UserType

DB의 `vector` 타입과 Java의 `float[]`를 서로 변환해주는 다리.

외부 라이브러리(pgvector-java, hypersistence-utils) 없이 직접 구현한 이유: PostgreSQL JDBC 드라이버의 `PGobject`만으로 구현 가능해서 의존성 최소화.

```java
public class VectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;  // pgvector는 JDBC 표준에 없어서 OTHER로 취급
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, ...) throws SQLException {
        // DB에서 읽을 때: "[0.1,0.2,...]" 문자열 -> float[] 파싱
        String value = rs.getString(position);
        if (rs.wasNull() || value == null) return null;
        String[] parts = value.substring(1, value.length() - 1).split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, ...) throws SQLException {
        // DB에 쓸 때: float[] -> PGobject(type="vector")로 변환
        if (value == null) { st.setNull(index, Types.OTHER); return; }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < value.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(value[i]);
        }
        sb.append("]");
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        pgObject.setValue(sb.toString());
        st.setObject(index, pgObject);
    }

    @Override
    public boolean equals(float[] x, float[] y) { return Arrays.equals(x, y); }
    // float[]는 기본 == 비교(참조 비교)라서 내용물 기준으로 오버라이드.
    // 안 하면 Hibernate 더티체킹이 같은 값도 "바뀌었다"고 오판해 불필요한 UPDATE 발생.

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
    // float[]는 mutable이라 원본과 별개의 복사본을 관리해야 Hibernate가 안전하게 상태 추적 가능.
}
```

Hibernate 스키마 검증 통과 원리: DB의 `vector(1024)` 컬럼은 JDBC에서 `Types.OTHER`로 반환되는데, `VectorType.getSqlType()`도 `Types.OTHER`를 반환하므로 타입 일치로 검증 통과.

---

## 3. 엔티티 수정

| 엔티티 | 변경 전 | 변경 후 |
|---|---|---|
| `Embedding.vector` | `String` / `columnDefinition="TEXT"` | `float[]` / `@Type(VectorType.class)` / `columnDefinition="vector(1024)"` |
| `SearchQuery.queryVector` | `String` / `columnDefinition="TEXT"` | `float[]` / `@Type(VectorType.class)` / `columnDefinition="vector(1024)"` |

- `Embedding.vector`: A담당자 소유 테이블. `nullable=false` — 임베딩 자체가 없으면 row가 없어야 함.
- `SearchQuery.queryVector`: B담당자(검색 블록) 소유. `nullable=true` — 임베딩 실패 시에도 검색 로그 자체는 남겨야 하기 때문.

참고: `Embedding` 엔티티에는 `dimension`(차원 수 검증용), `vectorHash`(중복 방지/변경 감지용) 필드도 있고, `document_id` / `document_version_id`는 조회 성능을 위한 역정규화 필드(chunk를 거치지 않고 바로 조회 가능하도록 미리 복사).

---

## 4. build.gradle 변경

```groovy
// 변경 전
runtimeOnly 'org.postgresql:postgresql'

// 변경 후
implementation 'org.postgresql:postgresql'
```

`VectorType.java`가 컴파일 시점에 `org.postgresql.util.PGobject`를 직접 import해서 사용하기 때문에, 런타임 전용 스코프(`runtimeOnly`)에서 컴파일 타임 포함 스코프(`implementation`)로 격상.

---

## 5. db/seed 정비

`R__seed_mock_embedding_model.sql`(MOCK 모델) 제거 → `R__seed_bge_m3_embedding_model.sql`로 교체.

```sql
-- 기존 active+searchable 모델 먼저 비활성화
-- (partial unique index: active+searchable은 1개만 허용)
UPDATE embedding_models
SET is_active = FALSE, is_searchable = FALSE
WHERE is_active = TRUE AND is_searchable = TRUE
  AND model_name != 'BAAI/bge-m3';

-- bge-m3 upsert
INSERT INTO embedding_models (provider, model_name, model_version, dimension, distance_metric, is_active, is_searchable, ...)
VALUES ('HUGGINGFACE', 'BAAI/bge-m3', '1.0', 1024, 'COSINE', TRUE, TRUE, ...)
ON CONFLICT (provider, model_name, model_version) DO UPDATE SET ...;
```

MOCK 모델을 먼저 비활성화하는 이유: `embedding_models`에 "active+searchable은 1개만 허용"하는 Partial Unique Index가 걸려있어서, 비활성화 없이 bge-m3를 바로 넣으면 unique 제약 위반.

`R__seed_test_fixtures.sql`: A담당자의 실제 문서 파이프라인이 아직 없어서, 개발/테스트용 더미 데이터(PUBLIC 문서 2개, 청크 4개, 임베딩 4개)를 직접 심어둠. 청크마다 벡터의 서로 다른 차원 구간에 값을 몰아넣어(청크0: 1~10번 차원, 청크1: 11~20번 차원…) "유사도 순위가 제대로 매겨지는지" 검증할 수 있게 설계. `ON CONFLICT DO NOTHING`으로 멱등 처리.

---

## 6. 트러블슈팅

#### V33 duplicate key 오류

원인: MOCK 모델이 active+searchable로 남아있어서 bge-m3 INSERT가 unique 제약에 막힘.  
해결: INSERT 전 UPDATE로 기존 모델 비활성화.

#### Hibernate 스키마 검증 실패

원인: DB는 `vector(1024)`인데 엔티티가 `TEXT`로 남아있어 타입 불일치.  
해결: VectorType 구현 + 엔티티 수정으로 해결.

#### PGobject 컴파일 에러

원인: `runtimeOnly` 스코프라 컴파일 타임에 `PGobject` 접근 불가.  
해결: `implementation`으로 변경.

#### flyway_schema_history 잔여 오류

원인: 삭제한 seed 파일의 기록이 히스토리 테이블에 남아 "applied migration not resolved locally" 오류 발생.  
해결: `DELETE FROM flyway_schema_history WHERE script = '...'`로 정리. 팀원도 로컬에 구 seed가 적용된 상태였다면 동일 문제 발생 가능 → 온보딩 안내 필요.

---

## 설계 결정 요약

| 결정 | 이유 |
|---|---|
| seed를 `db/migration`이 아닌 `db/seed`에 배치 | 데이터 seed는 스키마 변경이 아니므로 분리. `R__`(repeatable) 방식으로 멱등 관리 |
| VectorType 직접 구현 | 외부 라이브러리 없이 JDBC 드라이버만으로 처리 가능. 의존성 최소화 |
| `float[]` 선택 | 임베딩 서버 응답(`List<Float>`)을 직접 담을 수 있고 pgvector 연산과 자연스럽게 연결 |
| HNSW vs IVFFlat | HNSW가 실시간 insert 성능 우수. IVFFlat은 대규모 배치 인덱스 구축에 유리 |
