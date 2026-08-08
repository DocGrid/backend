# Issue #131 Exact Seq Scan·HNSW 규모별 성능 및 Recall 비교 상세 설계

## 1. 배경과 목적

현재 공식 OpenSQL 호환성 검증은 2,000개 `vector(1024)` Probe에서 HNSW Index Scan 사용 여부와
지연 분포를 확인한다. 이 결과만으로는 Index가 없는 Exact 검색보다 HNSW가 어느 데이터 규모부터
유리한지, 지연 감소와 함께 어떤 Recall 손실이 발생하는지 설명할 수 없다.

이번 작업은 PostgreSQL 17과 pgvector 0.8.1에서 동일한 Vector·Query를 사용해 다음 두 경로를
비교하는 반복 가능한 Benchmark를 추가한다.

```text
고정 Seed Vector 생성
→ 규모별 Probe Table 적재
→ Exact Seq Scan 기준 결과·지연 측정
→ HNSW Index 생성
→ HNSW Index Scan 결과·지연 측정
→ Exact Top-K 대비 Recall@10 계산
→ 실행 계획·용량·지연·Recall Report 기록
```

### 1.1 성공 기준

- 2,000 / 10,000 / 50,000개 `vector(1024)`를 같은 Seed로 생성한다.
- Exact 검색은 `Seq Scan`, HNSW 검색은 전용 HNSW `Index Scan`을 사용한다.
- 동일 Query 집합으로 두 검색 경로를 비교한다.
- p50·p95·p99·최대 지연과 Recall@10을 규모별로 기록한다.
- HNSW Index 생성 시간, Table 크기, Index 크기를 기록한다.
- 결과는 구조화된 JSON과 사람이 읽을 수 있는 결과 문서로 남긴다.
- 일반 `test`와 실제 성능 Benchmark 실행 경계를 분리한다.

## 2. 범위

### 2.1 포함

- PostgreSQL 17 + pgvector 0.8.1 전용 통합 Benchmark
- 설정 가능한 데이터 규모, Query 수, Warm-up 수, 측정 반복 수
- 고정 Seed 기반 정규화된 1024차원 Vector
- Exact Seq Scan과 Cosine HNSW Index Scan 실행 계획 검증
- Exact Top-K를 정답 집합으로 사용하는 Recall@10
- 지연 Percentile, Index 생성 시간, Relation 크기 수집
- 전용 Gradle Task와 `build/reports/` JSON 출력
- 로컬 실측 결과와 재현 절차 문서

### 2.2 제외

- IVFFlat·pgvectorscale와의 비교
- `m`, `ef_construction`, `ef_search` 전체 조합 탐색
- Product 검색 API·권한 Pre-filter·RAG 흐름 변경
- BGE-M3 Embedding 생성 성능
- 공식 OpenSQL 원격 서버 최종 성능 판정
- 장비에 독립적인 절대 SLO 확정

## 3. 실행 경계

Benchmark는 일반 회귀 테스트에 포함하지 않는다.

```text
./gradlew vectorSearchPerformanceTest
```

전용 Task는 `vector-search-performance` Tag만 실행하며 다음 System Property를 전달한다.

| Property | 기본값 | 역할 |
|---|---:|---|
| `vector.search.performance.sizes` | `2000,10000,50000` | 비교할 누적 Row 수 |
| `vector.search.performance.query-count` | `10` | 고정 Query Vector 수 |
| `vector.search.performance.warm-up` | `3` | 경로별 Warm-up 반복 |
| `vector.search.performance.measured-runs` | `10` | Query별 본 측정 반복 |
| `vector.search.performance.top-k` | `10` | 검색 결과와 Recall 기준 |
| `vector.search.performance.output` | `build/reports/vector-search/exact-vs-hnsw.json` | 구조화 결과 경로 |

Smoke 실행은 규모와 반복 수를 줄여 Test Harness 계약을 빠르게 확인한다.

```text
./gradlew vectorSearchPerformanceTest \
  -Dvector.search.performance.sizes=2000,10000 \
  -Dvector.search.performance.query-count=3 \
  -Dvector.search.performance.warm-up=1 \
  -Dvector.search.performance.measured-runs=2
```

## 4. 데이터와 Query 재현성

### 4.1 Vector 생성

- Java `Random`의 고정 Seed를 사용한다.
- 각 Row마다 1024개 값을 생성한 뒤 L2 Norm으로 정규화한다.
- 모든 규모는 같은 전체 Sequence의 Prefix를 사용한다.
- 가장 큰 규모까지 한 번 적재하고 규모별 경계에서 측정하는 방식은 Planner Statistics와 Index 상태를
  혼동할 수 있으므로, 각 Profile마다 전용 Table을 새로 준비한다.

### 4.2 Query 선택

- 같은 Seed에서 파생한 고정 Row ID를 Query 대상으로 사용한다.
- Query는 Table에 실제 존재하는 Vector를 사용하므로 Exact 결과의 첫 항목은 자기 자신, 거리는 0이다.
- 데이터 규모가 달라도 같은 비율 구간에서 Query ID가 선택되도록 균등 분포를 사용한다.
- Exact와 HNSW는 같은 Query Vector와 같은 순서로 실행한다.

## 5. 검색 경로 고정

### 5.1 Exact Seq Scan

같은 Connection에서 다음 Planner 설정을 적용한다.

```sql
SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
SET LOCAL enable_seqscan = on;
```

그 후 Cosine Top-K SQL을 실행한다.

```sql
SELECT id, vector <=> CAST(? AS vector) AS distance
FROM vector_search_performance_probe
ORDER BY vector <=> CAST(? AS vector)
LIMIT ?;
```

`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`의 전체 Plan Tree에 `Seq Scan`이 있고 HNSW Index 이름이
없어야 한다. Primary Key 조회는 Query Vector를 준비할 때만 사용하고 검색 측정에는 포함하지 않는다.

### 5.2 HNSW Index Scan

Exact 측정 후 같은 데이터에 다음 Index를 생성한다.

```sql
CREATE INDEX vector_search_performance_hnsw
ON vector_search_performance_probe
USING hnsw (vector vector_cosine_ops);
```

`ANALYZE` 뒤 같은 Connection에서 `enable_seqscan = off`를 적용해 HNSW 경로를 고정한다.
Plan Tree에 `Index Scan`과 전용 HNSW Index 이름이 있어야 한다.

강제 Planner 설정은 두 알고리즘의 기준선을 분리하기 위한 Benchmark 경계다. 실제 제품 Query에서
Planner가 어떤 경로를 선택하는지 보장하는 설정이 아니다.

## 6. 측정 방법

### 6.1 순서

각 데이터 규모는 다음 순서로 실행한다.

1. Probe Table 생성과 Batch Insert
2. `ANALYZE`
3. Exact 실행 계획 수집
4. Exact Warm-up과 본 측정
5. HNSW Index 생성 시간 측정
6. `ANALYZE`
7. HNSW 실행 계획 수집
8. HNSW Warm-up과 본 측정
9. Exact Query별 Top-K와 HNSW Top-K로 Recall@10 계산
10. Relation 크기와 결과 JSON 기록

### 6.2 지연

- Warm-up 결과는 Percentile에 포함하지 않는다.
- Query마다 여러 번 반복하고 전체 표본에서 p50·p95·p99·최대를 계산한다.
- JDBC 왕복과 Result Mapping을 포함한 Application 관측 지연을 사용한다.
- Hardware 의존적인 절대 지연값은 자동 실패 기준으로 사용하지 않는다.

### 6.3 Recall@10

각 Query의 Exact ID 집합을 정답으로 정의한다.

```text
Recall@10 = |Exact Top-10 ∩ HNSW Top-10| / 10
```

Query별 Recall을 계산하고 Profile에는 최소·평균 Recall을 기록한다. Recall은 0~1 범위를 만족해야 하며,
임계값 미달을 자동 실패로 만들지 않는다. 기본 HNSW 설정의 품질을 관측하는 것이 목적이기 때문이다.

### 6.4 저장 공간

- `pg_table_size(table)`로 Heap·TOAST·FSM·Visibility Map을 포함한 Table 저장 공간을 기록한다.
- `pg_relation_size(hnsw_index)`로 HNSW Index 크기를 기록한다.
- `pg_total_relation_size(table)`로 전체 Relation 크기를 기록한다.
- 값은 Byte 원본을 JSON에 저장하고 문서에는 MiB로 변환한다.

## 7. 구조화 결과

JSON은 실행 환경의 공개 가능한 정보와 Profile 배열을 가진다.

```json
{
  "serverVersion": "17.8",
  "pgvectorVersion": "0.8.1",
  "dimension": 1024,
  "topK": 10,
  "profiles": [
    {
      "rowCount": 2000,
      "exact": {"planNodes": ["Limit", "Sort", "Seq Scan"], "p95Ms": 0.0},
      "hnsw": {"planNodes": ["Limit", "Index Scan"], "p95Ms": 0.0},
      "averageRecallAtK": 1.0,
      "minimumRecallAtK": 1.0,
      "indexBuildMs": 0.0,
      "tableBytes": 0,
      "indexBytes": 0
    }
  ]
}
```

DB Host, Port, Database 이름, Username, Password와 JDBC URL은 결과에 기록하지 않는다.

## 8. Test 계약

- 잘못된 데이터 규모, Query 수, 반복 수, Top-K는 실행 전에 거부한다.
- 데이터 규모는 오름차순·중복 없음·Top-K 이상이어야 한다.
- Exact Plan은 `Seq Scan`, HNSW Plan은 전용 Index 이름을 포함해야 한다.
- 결과 개수, 거리 유한성, 거리 오름차순과 자기 자신 Top-1을 검증한다.
- Recall 계산은 Exact·HNSW ID 집합의 교집합을 사용한다.
- JSON에는 모든 요청 Profile이 입력 순서대로 한 번씩 기록돼야 한다.
- 실패해도 `finally`에서 현재 Test Schema의 Probe Table만 제거한다.

## 9. 위험과 해석 경계

| 위험 | 대응 |
|---|---|
| 50,000×1024 Vector 생성·Index 구축 비용 | 전용 Task로 분리하고 Smoke Property 제공 |
| Cache가 두 번째 경로에 유리 | 결과에 실행 순서를 명시하고 절대값보다 규모별 추세 중심으로 해석 |
| 강제 Planner 설정이 운영 선택과 다름 | 알고리즘 기준선 분리 목적임을 결과에 명시 |
| 단일 Query가 데이터 분포를 대표하지 못함 | 균등한 여러 Query와 고정 Seed 사용 |
| Random Vector가 실제 문서 분포와 다름 | Synthetic 기준선으로 한정하고 실제 문서 Corpus 비교는 후속으로 분리 |
| Local Docker 수치의 일반화 | 장비 Fingerprint와 실행 경계를 기록하고 SLO로 사용하지 않음 |

## 10. 커밋 분할

1. `docs: #131 Exact·HNSW 규모별 비교 설계 추가`
2. `test: #131 Exact·HNSW 규모별 Vector 검색 Benchmark 추가`
3. `build: #131 Vector 검색 성능 전용 실행 경계 추가`
4. `docs: #131 Exact·HNSW 규모별 실측 결과 기록`

## 11. 완료 조건

- 전용 Gradle Task가 세 데이터 규모를 한 명령으로 측정한다.
- Exact와 HNSW 실행 계획을 각각 검증한다.
- 지연 Percentile, Recall@10, Index 생성 시간과 Relation 크기가 JSON으로 생성된다.
- 로컬 PostgreSQL 17 + pgvector 0.8.1 실측 결과가 `docs/test-results/`에 기록된다.
- 일반 회귀 테스트가 통과한다.
- 민감정보와 대형 원시 Vector 데이터가 Git에 포함되지 않는다.
