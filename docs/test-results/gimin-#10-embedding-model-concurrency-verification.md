# 기본 임베딩 모델 단일성 및 동시성 검증

## 1. 문제 배경

신규 `embedding_job`이 사용할 기본 임베딩 모델은 `is_active = true`이면서
`is_searchable = true`인 행이다. 여러 서버가 동시에 "현재 기본 모델이 없다"고 조회한 뒤
각자 저장하면 애플리케이션 사전 조회만으로는 두 행이 함께 Commit되는 경쟁 조건이 생긴다.

검증 기준 Git 커밋은 `1bf956c`이며, PR 1 머지 커밋 `eecf255`가 포함된 최신
`develop`에서 측정했다.

## 2. is_active와 is_searchable을 분리한 이유

- `is_active`: 신규 embedding job이 선택할 모델인지 나타낸다.
- `is_searchable`: 기존에 생성된 Vector를 현재 검색에 사용할 수 있는지 나타낸다.

모델 교체 후에도 기존 모델을 `false + true`로 유지하면 신규 Job에는 사용하지 않으면서
기존 Vector 검색 가능성은 유지할 수 있다.

## 3. 애플리케이션 사전 조회만 사용할 때의 경쟁 조건

Partial Index가 없는 테스트 전용 비교 테이블에서 두 독립 트랜잭션이 각각 기본 모델 수를
조회하고 Barrier에서 만난 뒤 저장했다.

```text
시도: 2
두 트랜잭션의 사전 조회 결과: 각각 0
성공: 2
실패: 0
최종 true + true: 2
전체 시도 처리시간 median: 4.472063 ms
```

따라서 조회 후 저장 사이의 경쟁 조건 때문에 애플리케이션 사전 조회만으로 단일성을
완전히 보장할 수 없다.

## 4. 일반 UNIQUE(is_active, is_searchable)가 부적합한 이유

일반 Boolean 복합 Unique는 네 조합을 각각 한 건으로 제한한다. 실제 정책은 다음과 같다.

| is_active | is_searchable | 실제 저장 결과 |
|---|---|---:|
| false | false | 2건 성공 |
| true | false | 2건 성공 |
| false | true | 2건 성공 |
| true | true | 1건 성공, 두 번째 저장 차단 |

두 번째 true+true 저장이 실패한 뒤에도 총 7건과 각 상태별 개수가 유지됐다.

## 5. Partial Unique Index 선택 이유

`true + true` 행에만 같은 상수 표현식 `(1)`을 인덱싱하면 해당 Predicate를 만족하는 모든
행이 동일한 Unique Key를 갖는다. 그 외 Boolean 조합은 인덱스 대상이 아니므로 여러 행을
허용한다.

DB는 최대 한 개를 보장하고, 애플리케이션 Service는 0개 및 비정상 다중 결과를 명시적인
설정 오류로 처리한다.

## 6. 현재 Migration

`src/main/resources/db/migration/V27__add_embedding_model_constraints.sql`

```sql
CREATE UNIQUE INDEX uk_embedding_models_one_active_searchable
    ON embedding_models ((1))
    WHERE is_active = TRUE
      AND is_searchable = TRUE;
```

이번 검증에서는 V27을 수정하거나 인덱스를 재생성하지 않았다.

## 7. 실제 OpenSQL 환경

```text
Container image: tmaxopensql/postgres:14.6
Image digest: sha256:6d4cc9a80921acc315a97c8cad92aea631ae40983173e80f0e8c5a373967f713
DB response: PostgreSQL 14.6 on x86_64-pc-linux-gnu
Profile: test
정확성 테스트 스키마: docgrid_embedding_constraint_test
Benchmark 스키마: docgrid_embedding_benchmark_test
Flyway locations: classpath:db/migration, classpath:db/seed
Testcontainers/H2: 사용하지 않음
```

현재 로컬 `.env`의 `docgrid` 계정 및 SSL 비활성 설정과 기존 OpenSQL 볼륨의 `app` 계정 및
SSL 요구사항이 일치하지 않았다. 볼륨을 삭제하지 않고 테스트 명령에 현재 볼륨과 맞는
DB 이름·사용자·`sslmode=require`를 주입했다. 비밀번호와 JWT Secret은 문서에 기록하지 않는다.

## 8. Index 카탈로그 조회 결과

`pg_index`, `pg_class`, `pg_namespace`, `pg_get_expr()`, `pg_get_indexdef()`로 확인했다.

```text
index: uk_embedding_models_one_active_searchable
table: docgrid_embedding_constraint_test.embedding_models
indisunique: true
predicate: ((is_active = true) AND (is_searchable = true))
definition: CREATE UNIQUE INDEX uk_embedding_models_one_active_searchable
            ON docgrid_embedding_constraint_test.embedding_models USING btree ((1))
            WHERE ((is_active = true) AND (is_searchable = true))
```

## 9. Boolean 조합별 저장 결과

```text
false + false: 2
true  + false: 2
false + true : 2
true  + true : 1
total        : 7
```

두 번째 true+true 저장의 실제 결과:

```text
Spring exception: DataIntegrityViolationException
SQLSTATE: 23505
Index: uk_embedding_models_one_active_searchable
실패 후 true + true: 1
```

## 10. 동시 저장 비교 결과

| 비교 항목 | 애플리케이션 사전 조회만 | Partial Unique Index 적용 |
|---|---:|---:|
| 반복 | 1 | 20 |
| 저장 시도 | 2 | 40 |
| 성공 | 2 | 20 |
| 실패 | 0 | 20 |
| Unique 위반 | 0 | 20 |
| 예상 외 실패 | 0 | 0 |
| 최종 true+true | 2 | 매 반복 1 |
| 불변식 위반 | 있음 | 0회 |
| 실패 SQLSTATE | 해당 없음 | 23505 |
| 실패 Index | 해당 없음 | uk_embedding_models_one_active_searchable |
| 전체 시도 처리시간 median | 4.472063 ms | 1.776959 ms |

DB 제약 적용 후 한 요청이 실패하는 것은 시스템 장애가 아니라 잘못된 중복 상태를 차단한
결과다. 처리시간은 서로 다른 반복 조건의 로컬 관찰값이므로 성능 우열 근거로 사용하지 않는다.

## 11. 모델 교체 트랜잭션 결과

정상 순서:

```text
1. old-model: true + true -> false + true
2. new-model: false + false -> true + true
3. Commit 성공
4. 최종 true + true: 1
```

잘못된 순서:

```text
1. new-model을 먼저 true + true로 변경
2. SQLSTATE 23505 / uk_embedding_models_one_active_searchable
3. 트랜잭션 Rollback
4. old-model: true + true 유지
5. new-model: false + false 유지
```

## 12. Rollback 결과

기존 모델의 `is_active`를 false로 변경한 직후 테스트 예외를 발생시켰다.

```text
exception: IntentionalTestException
old-model: true + true 유지
new-model: false + false 유지
최종 true + true: 1
```

## 13. 조회 실행 계획 비교

합성 모델 100,000건 중 true+true는 한 건이었다.

적용 전:

```text
Node: Index Scan
Index: idx_benchmark_is_searchable
Filter: is_active
Rows removed by filter: 13,334
Actual rows: 1
Planning time: 0.015 ms
Execution time: 0.899 ms
Shared hit blocks: 748
Shared read blocks: 0
```

적용 후:

```text
Nodes: Bitmap Heap Scan -> Bitmap Index Scan
Index: uk_benchmark_one_active_searchable
Actual rows: 1
Planning time: 0.014 ms
Execution time: 0.009 ms
Shared hit blocks: 2
Shared read blocks: 0
```

Planner 문자열 전체를 테스트에서 고정하지 않고, 결과 한 건과 적용 후 partial index 사용만
자동 검증했다.

## 14. 성능 측정 결과

```text
Synthetic rows: 100,000
Warm-up: 5회
Measured runs: 20회

적용 전: median 1.084917 ms / min 1.037958 ms / max 1.297708 ms
적용 후: median 0.276500 ms / min 0.224708 ms / max 0.467959 ms
관찰된 median 변화율: 74.514% 감소

Table size: 6,029,312 bytes
Indexes before: 3,596,288 bytes
Indexes after: 3,612,672 bytes
Partial Index: 16,384 bytes
```

이 수치는 로컬 OpenSQL 컨테이너, 합성 데이터, 현재 캐시 상태에서 얻은 결과다. 실제 운영
트래픽의 성능 향상으로 일반화할 수 없다. 이 작업의 핵심 효과는 조회 속도가 아니라 경쟁
조건 제거와 데이터 불변식 보장이다.

## 15. 해결 전후 비교

| 항목 | PR 1 기준선 | PR 1-A |
|---|---:|---:|
| 전체 기본 테스트 | 17 | 26 |
| Repository 테스트 | 5 | 5 |
| Service 테스트 | 4 | 4 |
| Controller 테스트 | 3 | 3 |
| 동시성·트랜잭션 통합 테스트 | 0 | 9 |
| 실패 | 0 | 0 |
| 오류 | 0 | 0 |
| 스킵 | 0 | 0 |
| XML 테스트 시간 합계 | 0.445 s | 0.700 s |
| 별도 Benchmark | 없음 | 1개, 0.802 s |

테스트 수 증가는 성능 개선이 아니라 검증 및 회귀 방지 범위가 넓어진 것이다.

## 16. 한계와 해석 주의점

- 로컬 개발 장비의 단일 OpenSQL 컨테이너에서 실행했다.
- 동시성 테스트는 2개 Thread와 20회 반복으로 제한했다.
- Benchmark는 합성 데이터 100,000건이며 실제 운영 분포와 다르다.
- 절대 시간은 장비 부하와 캐시 상태에 따라 변한다.
- 인덱스 유지에 따른 쓰기 비용이나 운영 부하 테스트는 측정하지 않았다.
- DB는 최대 한 개만 보장한다. 기본 모델 0개 처리는 Service 책임이다.

## 17. 재현 명령어

먼저 저장소의 OpenSQL 컨테이너가 현재 볼륨의 계정과 일치하는 설정으로 healthy인지 확인한다.
아래 `<...>` 값은 로컬 테스트 환경변수로 주입하며 문서나 Git에 저장하지 않는다.

```bash
export JWT_SECRET='<test-only-jwt-secret>'
export DB_PASSWORD='<local-test-db-password>'
export DB_PORT=5432
export DB_NAME=app
export DB_USER=app
export DB_SSLMODE=require
```

정확성·동시성·트랜잭션 테스트:

```bash
./gradlew cleanTest test --tests '*EmbeddingModelConstraintIntegrationTest'
```

전체 테스트(Benchmark 제외):

```bash
./gradlew cleanTest test
```

Benchmark:

```bash
./gradlew benchmarkTest --rerun-tasks
```

결과 수치는 다음 XML에서 확인한다.

```text
build/test-results/test/TEST-*.xml
build/test-results/benchmarkTest/TEST-*.xml
```

두 테스트 전용 스키마는 각 테스트 클래스 종료 시 `DROP SCHEMA ... CASCADE`로 제거된다.

## 18. 배운 점

- 사전 조회는 사용자 친화적 오류나 빠른 실패에는 유용하지만 DB 불변식을 대체하지 못한다.
- 상수 표현식 기반 Partial Unique Index는 조건을 만족하는 행만 단일화할 수 있다.
- 모델 교체는 기존 기본 모델 해제와 신규 모델 활성화를 한 트랜잭션에서 순서대로 수행해야 한다.
- 정확성 테스트와 Benchmark를 분리해야 시간 변동이 회귀 테스트의 성공 여부를 왜곡하지 않는다.
- OpenSQL 검증에서는 이미지뿐 아니라 vars 파일, 볼륨에 초기화된 사용자, SSL 모드까지 함께 맞아야 한다.
