# Vector 저장 TPS·Batch Size 비교 Benchmark 설계

- 작성일: 2026-08-11
- 상태: 구현 및 로컬 실측 완료

## 1. 배경

DocGrid는 `vector(1024)`와 HNSW 검색 구조, Exact·HNSW 검색 지연과 Recall을 검증했다. 그러나 실제
Vector 저장 경로는 기능·원자성만 확인했고, 데이터 규모와 JDBC Batch Size가 저장 처리량에 미치는
영향은 측정하지 않았다. 검색이 빨라도 Embedding 결과를 DB에 넣는 단계가 느리면 전체 인덱싱 처리량의
병목이 될 수 있다.

이번 Benchmark는 실제 PostgreSQL 17 + pgvector에서 제품과 같은 1024차원 Vector·HNSW 구조를
사용해 1천·1만·10만 건 저장 TPS와 Batch 호출 수, 저장 공간을 비교한다.

## 2. 목표와 성공 기준

- 1,000·10,000·100,000건을 같은 Vector Pool과 Schema에서 비교한다.
- JDBC Batch Size 1·100·500·1,000을 같은 Transaction 경계로 비교한다.
- Vector 생성 시간은 측정에서 제외하고 Bind·Batch 전송·DB 반영·Commit을 포함한다.
- 모든 Profile에서 Row 수, `vector_dims=1024`, HNSW Index와 Batch 실행 횟수를 검증한다.
- Profile별 Warm-up 뒤 본 측정 2회를 실행해 Duration·TPS 분포를 기록한다.
- 결과를 재현 가능한 JSON과 `docs/test-results/` 문서로 남긴다.
- 제품 Table, Migration, Repository와 운영 설정은 변경하지 않는다.

## 3. 비교 Profile

| 축 | 값 |
|---|---|
| 저장 건수 | `1,000`, `10,000`, `100,000` |
| JDBC Batch Size | `1`, `100`, `500`, `1,000` |
| Vector | Dense 1024차원, Seed 고정 Normalized Vector Pool |
| Index | `vector_cosine_ops`, HNSW `m=16`, `ef_construction=64` |
| Transaction | Profile Round당 한 Transaction, 마지막 Commit 포함 |
| Warm-up | Profile별 최대 1,000건 1회 |
| 본 측정 | Profile별 2회 |

총 12개 Profile을 측정한다. Batch Size가 Row 수보다 큰 경우 한 번의 `executeBatch()`로 처리한다.

## 4. 측정 경계

### 4.1 포함

1. PreparedStatement Parameter Bind
2. `addBatch()`
3. 설정한 크기마다 `executeBatch()`
4. PostgreSQL `vector(1024)` 저장과 HNSW Online Index 갱신
5. Transaction Commit

### 4.2 제외

- BGE-M3 추론과 HTTP 전송
- Chunk 생성과 JPA Entity 변환
- Connection 획득과 Table·Index 생성
- Vector 값 생성과 문자열 직렬화
- HNSW 신규 Index 일괄 구축 시간
- 검색 지연과 Recall

Vector 문자열은 측정 전에 1,024개를 결정적으로 만들어 재사용한다. 저장 자체가 아닌 Random Vector
생성·정규화·문자열 변환 비용이 TPS에 섞이지 않게 하기 위해서다.

## 5. 격리와 실행 순서

- Test마다 고유 Schema를 사용하고 종료 시 Schema 전체를 삭제한다.
- 전용 Probe Table만 생성하며 제품 `embeddings` Table은 사용하지 않는다.
- HNSW Index는 빈 Table에 먼저 생성해 Online Insert 비용을 모든 Profile에 동일하게 포함한다.
- Warm-up과 각 측정 Round 앞에서 `TRUNCATE`해 같은 빈 Table 상태에서 시작한다.
- 데이터 규모마다 Batch Size 시작 순서를 회전해 실행 순서 편향을 줄인다.
- 전용 Gradle Task는 직렬로 실행하고 일반 `test`에서는 제외한다.

## 6. 지표와 불변식

| 지표 | 계산·검증 |
|---|---|
| Duration | 첫 Bind 직전부터 Commit 완료까지 |
| TPS | `rowCount / durationSeconds` |
| Batch 실행 수 | 실제 `executeBatch()` 호출 횟수 |
| 기대 Batch 수 | `ceil(rowCount / batchSize)` |
| Row 수 | Profile Round 종료 뒤 정확히 입력 건수와 일치 |
| Vector 차원 | `min(vector_dims)=max(vector_dims)=1024` |
| Table·Index 크기 | `pg_table_size`, `pg_relation_size` |
| HNSW 계약 | `pg_indexes.indexdef`에 전용 Index와 `USING hnsw` 존재 |

`executeBatch()` 결과에 `EXECUTE_FAILED`가 있거나 기대 행 수와 결과 수가 다르면 즉시 실패한다. 측정
오류를 낮은 TPS처럼 기록하지 않는다.

## 7. 결과 해석 원칙

1. 같은 데이터 규모 안에서 Batch Size별 TPS와 Batch 호출 감소를 비교한다.
2. 1천→1만→10만 건에서 같은 Batch Size의 TPS가 어떻게 변하는지 확인한다.
3. 가장 높은 TPS 하나만 고르지 않고 100·500·1,000의 개선 폭이 작아지는 지점을 찾는다.
4. 로컬 Docker 절대 TPS는 운영 SLO가 아니라 상대 비교 기준선으로 사용한다.
5. 결과만으로 제품 Repository 저장 방식을 변경하지 않고 Pipeline E2E에서 다시 검증한다.

## 8. 실행 방법

```bash
docker compose up -d postgres
DB_SSLMODE=disable ./gradlew vectorStoragePerformanceTest
```

Smoke 실행은 다음처럼 규모와 Round를 줄인다.

```bash
DB_SSLMODE=disable ./gradlew vectorStoragePerformanceTest \
  -Dvector.storage.performance.sizes=1000 \
  -Dvector.storage.performance.batch-sizes=1,100 \
  -Dvector.storage.performance.measured-runs=1
```

기본 결과는 `build/reports/vector-storage/vector-storage-latest.json`에 생성한다.

실측 결과와 원본 데이터는 다음 파일에 보존한다.

- `docs/test-results/gimin-vector-storage-batch-benchmark.md`
- `docs/test-results/gimin-vector-storage-batch-benchmark-data.json`

## 9. 완료 조건

- 전용 Task가 12개 Profile을 실제 PostgreSQL에서 완료한다.
- 1천·1만·10만 건과 Batch 1·100·500·1,000 결과가 모두 기록된다.
- Row·차원·HNSW·Batch 호출 불변식이 자동 검증된다.
- 일반 회귀 테스트가 실제 대량 적재 없이 성공한다.
- JSON과 최종 결과 문서에 환경·수치·결론·한계가 기록된다.
