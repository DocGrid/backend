# Vector 저장 TPS·Batch Size 비교 결과

- 측정일: 2026-08-11
- 결과: 성공
- 원본 데이터: [gimin-vector-storage-batch-benchmark-data.json](gimin-vector-storage-batch-benchmark-data.json)
- 설계: [gimin-#155-vector-storage-batch-benchmark.md](../design/gimin-%23155-vector-storage-batch-benchmark.md)

## 1. 결론

실제 PostgreSQL 17.8과 pgvector 0.8.1에서 1,000·10,000·100,000개의 1024차원 Vector를
HNSW Index가 이미 존재하는 Table에 저장했다. JDBC Batch는 데이터가 커질수록 효과가 뚜렷했다.

- 100,000건 기준 Batch 1의 p50은 `1,130.06 rows/s`, Batch 1,000은 `1,631.31 rows/s`였다.
- Batch 1,000은 Batch 1보다 처리량이 `44.36%` 높고, p50 저장 시간은 약 `22.63초` 짧았다.
- 같은 조건에서 `executeBatch()` 호출은 `100,000회 → 100회`로 `99.9%` 감소했다.
- Batch 100도 `1,559.21 rows/s`로 Batch 1,000 성능의 약 `95.58%`를 확보했다.
- 1,000건에서는 Batch 100이 가장 빨랐다. 작은 입력에 과도한 Batch를 적용할 이점은 확인되지 않았다.

따라서 대량 Vector 적재의 로컬 기준선은 Batch 100~1,000이 타당하다. 다만 이번 결과만으로 제품의
기본 Batch Size를 확정하지 않고, 실제 Chunk·Embedding Pipeline의 메모리와 Transaction 시간을 함께
측정한 뒤 결정한다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| Database | PostgreSQL 17.8 (Debian 17.8-1.pgdg12+1) |
| pgvector | 0.8.1 |
| JDBC Driver | PostgreSQL JDBC Driver 42.7.11 |
| Architecture | aarch64 |
| Java | 17.0.18 |
| Vector | 1024차원, Seed 고정 Normalized Vector Pool 1,024개 |
| HNSW | cosine, `m=16`, `ef_construction=64` |
| Warm-up | Profile별 최대 1,000건 |
| 본 측정 | Profile별 2회 |
| 측정 경계 | 첫 Bind 직전부터 HNSW Online 갱신과 Commit 완료까지 |

## 3. 전체 결과

TPS와 시간은 각 지표의 2회 표본을 독립적으로 정렬해 계산한 nearest-rank p50이다. 따라서 같은
Profile의 TPS p50과 시간 p50도 서로 다른 Round에서 나올 수 있으며, 두 값을 서로 환산하면 안 된다.
표본 수가 작으므로 절대 성능 SLO가 아니라 같은 로컬 환경의 상대 기준선으로 해석한다.

| 저장 건수 | Batch Size | TPS p50 | 시간 p50 | Batch 호출 | Batch 1 대비 TPS | 총 저장 크기 |
|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 1 | 414.81 | 2.389초 | 1,000 | 기준 | 13.23 MiB |
| 1,000 | 100 | **472.13** | **2.116초** | 10 | **+13.82%** | 13.23 MiB |
| 1,000 | 500 | 467.97 | 2.119초 | 2 | +12.82% | 13.23 MiB |
| 1,000 | 1,000 | 464.08 | 2.133초 | 1 | +11.88% | 13.23 MiB |
| 10,000 | 1 | 1,143.18 | 8.708초 | 10,000 | 기준 | 61.38 MiB |
| 10,000 | 100 | 1,524.13 | 6.467초 | 100 | +33.32% | 61.38 MiB |
| 10,000 | 500 | 1,397.54 | 6.922초 | 20 | +22.25% | 61.40 MiB |
| 10,000 | 1,000 | **1,533.76** | **6.495초** | 10 | **+34.17%** | 61.38 MiB |
| 100,000 | 1 | 1,130.06 | 83.220초 | 100,000 | 기준 | 732.80 MiB |
| 100,000 | 100 | 1,559.21 | 61.827초 | 1,000 | +37.98% | 733.44 MiB |
| 100,000 | 500 | 1,580.80 | 62.624초 | 200 | +39.89% | 732.71 MiB |
| 100,000 | 1,000 | **1,631.31** | **60.590초** | 100 | **+44.36%** | 734.47 MiB |

## 4. 해석

### 4.1 Batch가 줄인 비용

Batch Size가 커지면 JDBC 왕복과 `executeBatch()` 호출 수가 감소했다. 특히 100,000건에서 Batch 100은
호출 수를 99%, Batch 1,000은 99.9% 줄였다. 실제 TPS도 함께 개선됐으므로 호출 감소가 단순한 코드상
차이가 아니라 저장 처리량에 영향을 줬다.

### 4.2 처리량이 무한히 증가하지 않는 이유

모든 Profile은 HNSW Index를 먼저 만든 뒤 Vector를 Online Insert했다. Batch 100~1,000에서도 처리량이
약 1.6천 rows/s 부근에 머문 것은 JDBC 호출 외에 1024차원 Vector 저장과 HNSW 갱신 비용이 남아 있기
때문이다. 이 Benchmark는 빠른 Bulk Import가 아니라 실제 온라인 인덱싱 경로에 가까운 기준선이다.

### 4.3 단조 증가를 주장하지 않는 이유

10,000건의 Batch 500은 Batch 100과 1,000보다 낮았다. Profile별 본 측정이 2회이고 로컬 Docker의
I/O·CPU 변동이 있으므로 모든 규모에서 Batch가 커질수록 TPS가 반드시 증가한다고 결론 내릴 수 없다.
운영 기본값을 고르기 전 5회 이상 반복하고 실제 Chunk 크기·Worker 동시성을 포함해 재검증해야 한다.

### 4.4 저장 공간

100,000건의 Table은 약 533.2 MiB, HNSW Index는 약 199.5~201.2 MiB였다. Batch Size는 전송 방식만
바꾸므로 관계 크기는 실질적으로 같았고, 작은 차이는 HNSW 구성과 Page 배치 변동 범위였다.

## 5. 자동 검증

각 Round에서 다음 조건을 통과해야 결과에 포함되도록 구현했다.

- 저장 Row 수가 요청 건수와 정확히 일치
- `min(vector_dims)=max(vector_dims)=1024`
- 실제 Batch 실행 수가 `ceil(rowCount / batchSize)`와 일치
- JDBC 결과에 `EXECUTE_FAILED`가 없음
- `vector_cosine_ops` HNSW Index가 존재
- 저장 시간과 TPS가 0보다 큰 유한값

## 6. 재현 방법

```bash
docker compose up -d postgres
DB_SSLMODE=disable ./gradlew vectorStoragePerformanceTest
```

기본 실행은 12개 Profile을 순차 측정하고
`build/reports/vector-storage/vector-storage-latest.json`을 생성한다.

## 7. 한계와 다음 검증

- 로컬 Docker 단일 환경 결과이므로 공식 OpenSQL 수치와 동일하다고 볼 수 없다.
- 본 측정 2회는 회귀 기준선에는 쓸 수 있지만 안정적인 백분위 산출에는 부족하다.
- BGE-M3, Chunk 생성, HTTP, JPA와 Worker 동시 실행 비용은 제외했다.
- 제품 Batch Size 변경 전 공식 OpenSQL과 실제 Pipeline E2E에서 메모리·Connection Pool·Transaction
  시간을 함께 검증한다.
