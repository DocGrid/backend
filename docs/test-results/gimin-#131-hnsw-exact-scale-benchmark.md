# Issue #131 Exact Seq Scan·HNSW 규모별 성능 및 Recall 비교 결과

## 1. 결과 요약

PostgreSQL 17.8과 pgvector 0.8.1에서 2,000 / 10,000 / 50,000개의 Synthetic
`vector(1024)`를 같은 Seed로 생성하고 Exact Seq Scan과 HNSW Index Scan을 비교했다.

| Vector 수 | Exact p95 | HNSW p95 | p95 배율 | 평균 Recall@10 | 최소 Recall@10 |
|---:|---:|---:|---:|---:|---:|
| 2,000 | 5.103 ms | 1.139 ms | 4.48배 | 0.96 | 0.90 |
| 10,000 | 25.874 ms | 2.015 ms | 12.84배 | 0.52 | 0.10 |
| 50,000 | 197.611 ms | 1.994 ms | 99.09배 | 0.22 | 0.00 |

HNSW의 지연 이점은 데이터가 커질수록 분명해졌다. 반면 기본 `ef_search=40`은 이번 1024차원 Random
Vector 분포에서 10,000개부터 Recall이 크게 낮아졌다. 이 결과는 HNSW 사용 여부만으로 검색 품질이
보장되지 않으며 `ef_search`와 실제 문서 Corpus를 별도로 조정·측정해야 한다는 근거다.

## 2. 공개 가능한 실행 환경

| 항목 | 값 |
|---|---|
| Database | PostgreSQL 17.8 |
| pgvector | 0.8.1 |
| Database 실행 | Local Docker |
| Application Architecture | `aarch64` |
| Java | 17.0.18 |
| Vector 차원 | 1024 |
| Top-K | 10 |
| HNSW `m` | 16 |
| HNSW `ef_construction` | 64 |
| HNSW `ef_search` | 40 |
| Query 수 | Profile당 10개 |
| Warm-up | 경로별 Query당 3회 |
| 본 측정 | 경로별 Query당 10회, 총 100개 표본 |
| 실행 일자 | 2026-08-09 KST |

DB Host·Database 이름·Username·Password와 JDBC URL은 결과에 기록하지 않았다. 이번 결과는 Local
개발 장비의 기준선이며 공식 OpenSQL 원격 Server 성능이나 운영 SLO가 아니다.

## 3. 실행 방법

로컬 PostgreSQL 17 + pgvector Container가 정상 상태인 환경에서 실행했다.

```bash
DB_PORT=5432 DB_SSLMODE=disable \
  ./gradlew vectorSearchPerformanceTest --rerun-tasks
```

구조화 결과는 Git에 포함하지 않는 다음 경로에 생성된다.

```text
build/reports/vector-search/exact-vs-hnsw.json
```

빠른 Harness 확인에는 다음 Smoke Profile을 사용했다.

```bash
DB_PORT=5432 DB_SSLMODE=disable \
  ./gradlew vectorSearchPerformanceTest --rerun-tasks \
  -Dvector.search.performance.sizes=2000,10000 \
  -Dvector.search.performance.query-count=3 \
  -Dvector.search.performance.warm-up=1 \
  -Dvector.search.performance.measured-runs=2 \
  -Dvector.search.performance.output=build/reports/vector-search/exact-vs-hnsw-smoke.json
```

## 4. 실행 계획 검증

세 Profile에서 동일하게 다음 Plan Node를 확인했다.

### 4.1 Exact 기준선

```text
Limit
└─ Sort
   └─ Seq Scan: vector_search_performance_probe
```

- `enable_indexscan=off`
- `enable_bitmapscan=off`
- `enable_seqscan=on`
- 전용 HNSW Index 이름 없음

### 4.2 HNSW

```text
Limit
└─ Index Scan: vector_search_performance_hnsw
```

- `enable_seqscan=off`
- Index: `vector_search_performance_hnsw`
- Operator Class: `vector_cosine_ops`

Planner 강제 설정은 두 알고리즘의 기준선을 분리하기 위한 Benchmark 조건이다. 제품 Query에서 Planner가
항상 같은 경로를 자동 선택한다는 의미는 아니다.

## 5. 지연 결과

| Vector 수 | 경로 | p50 | p95 | p99 | 최대 |
|---:|---|---:|---:|---:|---:|
| 2,000 | Exact | 4.764 ms | 5.103 ms | 6.033 ms | 6.484 ms |
| 2,000 | HNSW | 0.861 ms | 1.139 ms | 1.176 ms | 1.232 ms |
| 10,000 | Exact | 23.342 ms | 25.874 ms | 33.383 ms | 42.169 ms |
| 10,000 | HNSW | 1.389 ms | 2.015 ms | 2.293 ms | 2.389 ms |
| 50,000 | Exact | 168.723 ms | 197.611 ms | 229.277 ms | 233.128 ms |
| 50,000 | HNSW | 1.388 ms | 1.994 ms | 2.112 ms | 2.749 ms |

Exact p95는 데이터 규모가 2,000개에서 50,000개로 25배 증가하는 동안 약 38.72배 증가했다. HNSW
p95는 같은 구간에서 약 1.75배 증가했다. 이번 분포에서는 HNSW가 데이터 증가에 따른 검색 지연 증가를
크게 억제했지만, 아래 Recall 결과와 함께 해석해야 한다.

## 6. Recall@10 결과

각 Query의 Exact Top-10 ID 집합을 정답으로 사용했다.

```text
Recall@10 = |Exact Top-10 ∩ HNSW Top-10| / 10
```

| Vector 수 | 평균 Recall@10 | 최소 Recall@10 |
|---:|---:|---:|
| 2,000 | 0.96 | 0.90 |
| 10,000 | 0.52 | 0.10 |
| 50,000 | 0.22 | 0.00 |

50,000개 Profile의 일부 Query는 Exact Top-10과 HNSW Top-10이 겹치지 않았다. 검색이 실패한 것은
아니며 HNSW는 항상 10개의 유한한 거리 결과를 오름차순으로 반환했다. 그러나 속도만으로 현재
`ef_search=40`을 운영 기본값으로 확정할 수는 없다.

이번 작업은 HNSW Parameter 조합 탐색을 제외했다. 후속 ANN Recall 비교에서는 최소한 다음을 같은
Corpus로 측정해야 한다.

- `ef_search`: 40 / 80 / 120 / 200
- p50·p95와 Recall@10의 동시 변화
- 실제 BGE-M3 문서 Embedding 분포
- 동시 Query에서 Connection별 `ef_search` 적용 경계

## 7. Index 구축 및 저장 공간

`tableBytes`는 1024차원 Vector가 저장될 수 있는 TOAST를 포함하도록 `pg_table_size`로 측정했다.

| Vector 수 | Index 구축 | Table | HNSW Index | 전체 Relation |
|---:|---:|---:|---:|---:|
| 2,000 | 1.103초 | 10.73 MiB | 15.63 MiB | 26.43 MiB |
| 10,000 | 9.979초 | 53.38 MiB | 78.13 MiB | 131.74 MiB |
| 50,000 | 276.820초 | 266.66 MiB | 390.63 MiB | 658.38 MiB |

첫 실행에서는 `pg_relation_size(table)`가 Main Fork만 집계해 TOAST 공간을 제외하는 문제를 발견했다.
측정 코드를 `pg_table_size(table)`로 수정하고 전체 Profile을 다시 실행했다. 최종 표는 수정 후 두 번째
정식 실행 결과다.

50,000개 HNSW Index는 Table 저장 공간의 약 1.46배였고 구축에 약 4분 37초가 걸렸다. 검색 지연
개선과 별도로 Index 저장 공간, 구축 시간과 배포·재색인 시간을 고려해야 한다.

## 8. 반복 실행 관찰

용량 집계 수정 전 첫 정식 실행도 같은 Vector·Query·Parameter로 완료됐다. 용량 수치는 제외하고 지연과
Recall 방향을 비교했다.

| Vector 수 | 1차 Exact/HNSW p95 | 2차 Exact/HNSW p95 | 1차 평균 Recall | 2차 평균 Recall |
|---:|---:|---:|---:|---:|
| 2,000 | 5.216 / 1.123 ms | 5.103 / 1.139 ms | 0.94 | 0.96 |
| 10,000 | 21.965 / 1.422 ms | 25.874 / 2.015 ms | 0.62 | 0.52 |
| 50,000 | 155.717 / 2.408 ms | 197.611 / 1.994 ms | 0.27 | 0.22 |

두 실행 모두 데이터 규모가 커질수록 HNSW의 지연 이점이 증가하고 Recall이 감소하는 방향은 같았다.
다만 고정 입력이어도 HNSW 그래프 구축과 실행 환경 영향으로 지연·Recall 값은 완전히 같지 않았다.
따라서 한 번의 숫자를 절대값으로 해석하지 않고 반복 측정의 범위와 추세를 함께 봐야 한다.

## 9. 테스트 결과

| 검증 | 결과 |
|---|---|
| Benchmark 계약 단위 테스트 | PASS, 5 tests |
| 2,000·10,000 Smoke Profile | PASS, 21초 |
| 첫 정식 Profile | PASS, 5분 25초 |
| TOAST 포함 용량 수정 후 정식 Profile | PASS, 5분 36초 |
| 전체 Java 회귀 | PASS, 717 tests, failure/error/skipped 0 |
| `git diff --check` | PASS |

전체 회귀의 첫 시도는 외부 셸의 SSL 설정이 로컬 비-SSL PostgreSQL에 적용돼 Spring/Flyway 초기화
37건이 연쇄 실패했다. `DB_SSLMODE=disable`을 Test Process에 명시한 재실행에서 717개가 모두 통과했다.
이는 Source나 새 Benchmark의 실패가 아니라 실행 환경 불일치였으며, 실패 시도도 통과 결과로 세지 않았다.

## 10. 결론과 남은 한계

- 구현됨: Exact Seq Scan과 HNSW Index Scan을 같은 입력으로 규모별 비교할 수 있다.
- 구현됨: 지연·Recall@10·Index 구축 시간·TOAST 포함 저장 공간이 JSON에 기록된다.
- 검증됨: 50,000개까지 HNSW 지연 이점과 기본 `ef_search=40`의 Recall 저하를 함께 관측했다.
- 한계: Random Vector는 실제 BGE-M3 문서 Embedding의 군집 분포를 대표하지 않는다.
- 한계: Local Docker 결과이며 공식 OpenSQL 원격 Server나 운영 SLO가 아니다.
- 후속: 실제 Corpus에서 `ef_search`별 지연·Recall Pareto Curve를 측정해야 한다.
