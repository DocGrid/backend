# 자동 Worker 전체 문서 인덱싱 처리량 Benchmark 설계

## 1. 배경

자동 Worker는 Job 실행 슬롯을 먼저 확보한 뒤 `PENDING` Job을 Claim하고, Parsing, Chunk 저장,
Batch Embedding, Vector 저장과 검색 Version 전환까지 수행한다. 기존 통합·E2E 테스트는 상태 전이와
소유권 정합성을 검증하지만 여러 문서를 연속 처리할 때의 처리량과 지연 기준선은 제공하지 않는다.

이번 작업은 실제 PostgreSQL 17, pgvector, MinIO와 `BAAI/bge-m3`를 연결한 자동 Worker 전체
Pipeline을 반복 측정해 다음 질문에 답한다.

1. 기본 Worker 동시성에서 문서와 Chunk를 초당 얼마나 인덱싱하는가?
2. 업로드 접수, Queue 대기와 실제 처리 시간 중 어느 구간이 전체 지연을 지배하는가?
3. Queue를 모두 소진할 때 처리 누락, 중복 Attempt 또는 중복 Vector가 발생하지 않는가?

## 2. 범위

### 2.1 포함

- 실제 인증과 Multipart HTTP 문서 업로드
- 실제 MinIO Object 저장과 읽기
- 실제 자동 Polling, Claim, Attempt, Lease 갱신과 전체 인덱싱 Pipeline
- 실제 BGE-M3 Batch Embedding과 PostgreSQL `vector(1024)` 저장
- 결정적 TXT Corpus와 같은 Chunk 분포를 사용한 반복 측정
- Warm-up과 본 측정 분리
- 문서·Chunk·Embedding 처리량과 Queue·처리·전체 지연 수집
- Profile별 상태, Attempt, Event, Chunk와 Vector 정합성 검증
- PostgreSQL, pgvector, BGE, Batch Size와 Worker 설정 환경 지문 수집
- Git 제외 JSON 원본 결과와 실행 결과 Markdown 기록
- 일반 테스트와 분리된 전용 Gradle Task

### 2.2 제외

- Worker 프로세스 수에 따른 수평 확장 비교
- Queue 허용 한계와 Backpressure 붕괴 지점 측정
- Lease 갱신 주기별 DB 부하 비교
- BGE-M3 Batch Size 재선정
- PDF·DOCX Parser 형식별 성능 비교
- 공식 OpenSQL 공급사 환경의 최종 성능 수치
- 운영 SLO 확정

## 3. Workload 계약

### 3.1 결정적 문서

- 모든 Profile은 같은 UTF-8 TXT Corpus Template을 사용한다.
- 문서마다 고유한 식별 문구만 바꾸고 본문 길이와 문단 구조는 동일하게 유지한다.
- 본문은 기본 Chunk Size와 Overlap에서 같은 수의 Chunk가 생성되도록 고정한다.
- 파일 이름과 Document 제목은 Profile, 반복과 문서 순번을 포함해 충돌을 방지한다.
- 전체 Profile의 실제 Chunk 수가 같지 않으면 처리량 비교를 실패로 처리한다.

### 3.2 Profile

기본값은 짧은 로컬 실행과 Queue가 유지되는 본 측정을 함께 제공한다.

| 구분 | 문서 수 | 반복 | 통계 포함 |
|---|---:|---:|---|
| Warm-up | 4 | 1 | 제외 |
| 작은 Queue | 16 | 3 | 포함 |
| 지속 Queue | 32 | 3 | 포함 |

문서 수와 반복은 `worker.indexing.throughput.*` System Property로 변경할 수 있다. Worker
`max-concurrency`는 제품 기본값인 `2`로 고정하고 결과 환경 지문에 기록한다. Worker 수평 확장은
별도 Benchmark에서 다룬다.

### 3.3 측정 구간

1. Warm-up 문서를 업로드하고 모두 `INDEXED`가 될 때까지 기다린다.
2. Profile 시작 시각을 기록하고 여러 Uploader Thread로 측정 문서를 접수한다.
3. 마지막 업로드 완료 시각을 기록한다.
4. 자동 Worker가 Profile의 모든 Job을 `INDEXED`로 전환할 때까지 기다린다.
5. 상태와 결과 정합성을 검증한 뒤 통계를 계산한다.

Upload와 Worker 실행이 겹치는 실제 동작을 유지한다. 대신 업로드 시간과 마지막 업로드 뒤 Queue
소진 시간을 분리해 HTTP·MinIO 접수가 Worker 처리량을 가리는지 확인한다.

## 4. 지표 계약

| 지표 | 계산 |
|---|---|
| documents/s | 완료 문서 수 / Profile 전체 경과 시간 |
| documents/min | documents/s × 60 |
| chunks/s | 저장 Chunk 수 / Profile 전체 경과 시간 |
| embeddings/s | 저장 Embedding 수 / Profile 전체 경과 시간 |
| Upload 경과 | 첫 Upload 시작부터 마지막 Upload 응답까지 |
| Queue 소진 | 마지막 Upload 응답부터 마지막 `INDEXED`까지 |
| Queue 대기 | Job `created_at`부터 `LOCKED` Event까지 |
| 실제 처리 | `LOCKED`부터 `INDEXED` Event까지 |
| 전체 Job 지연 | Job `created_at`부터 `INDEXED` Event까지 |

지연 분포는 선형 보간 p50·p95·p99와 max를 밀리초로 기록한다. 모든 Profile 원본과 중앙값 요약은
JSON으로 기록한다.

## 5. 정합성 계약

각 Profile은 측정 뒤 다음 조건을 모두 검증한다.

- 대상 Job 전부 `INDEXED`
- 전체 Schema에 `PENDING` 또는 `PROCESSING` 잔여 Job 없음
- Job별 `SUCCESS` Attempt 정확히 1개
- Job별 `LOCKED`, `PARSE_STARTED`, `CHUNKED`, `EMBEDDING_STARTED`, `INDEXED` 순서 유지
- Document와 Document Version 모두 `INDEXED`
- `documents.current_version_id`가 측정 Version을 가리킴
- 문서별 Chunk 수와 Embedding 수 일치
- 같은 `(chunk_id, embedding_model_id)` 중복 없음
- 모든 Vector 차원 1024
- 실패 Attempt, Retry와 최종 실패 Event 없음

정합성 실패가 하나라도 발생하면 성능 숫자를 유효한 결과로 취급하지 않고 Test를 실패시킨다.

## 6. 환경 검증과 결과 보존

Benchmark 시작 전에 다음을 확인한다.

- PostgreSQL Server Version `17.x`
- pgvector Extension `0.8.1`
- 전용 Test Schema와 MinIO Bucket
- BGE Health와 Model명 `BAAI/bge-m3`
- Document Batch Size와 Worker 최대 동시성

접속 Secret, JWT와 Object Storage Credential은 결과에 기록하지 않는다. 원본 JSON은 다음 Git 제외
경로에 생성한다.

```text
build/reports/worker-indexing-throughput/worker-indexing-throughput.json
```

실행 환경, 중앙값, 해석과 한계만 `docs/test-results/`에 기록한다.

## 7. 실행 경계

일반 `./gradlew test`는 Docker와 실제 BGE-M3에 의존하지 않는다. 전용 Task만 실제 Infrastructure를
요구한다.

```bash
docker compose up -d postgres minio embedding-server
./gradlew workerIndexingThroughputTest
```

확장 측정 예시는 다음과 같다.

```bash
./gradlew workerIndexingThroughputTest \
  -Dworker.indexing.throughput.document-counts=32,64 \
  -Dworker.indexing.throughput.repetitions=5
```

## 8. 실패 정책

- Infrastructure Health, DB Version, pgvector Version 또는 BGE Model 계약이 다르면 즉시 실패한다.
- Upload, Worker 처리, Embedding 또는 상태 Polling이 제한 시간을 넘으면 Job Snapshot을 포함해 실패한다.
- 완료된 Profile 결과는 후속 진단을 위해 JSON에 보존하되 실패 Profile은 중앙값 판단에서 제외한다.
- Profile 간 데이터는 같은 전용 Schema에서 고유 식별자로 격리하고 마지막에 Schema와 Bucket을 정리한다.

## 9. 검증

- Percentile, 중앙값과 처리량 계산 단위 테스트
- 실제 Infrastructure를 사용하는 작은 Smoke Benchmark
- 기본 Profile 전체 Benchmark
- 기존 로컬 문서 인덱싱 E2E 회귀
- 전체 일반 Java 테스트

## 10. 커밋 분할

1. `docs: #133 자동 Worker 처리량 Benchmark 설계 추가`
2. `test: #133 자동 Worker 전체 인덱싱 처리량 Benchmark 추가`
3. `build: #133 Worker 처리량 전용 실행 경계 추가`
4. `perf: #133 자동 Worker 인덱싱 처리량 실측 결과 기록`

## 11. 완료 조건

- 실제 자동 Worker 전체 Pipeline을 한 명령으로 반복 측정할 수 있다.
- 문서·Chunk·Embedding 처리량과 Queue·처리·전체 지연이 구조화돼 기록된다.
- 성능 Profile마다 완료·소유권·Attempt·Event·Vector 불변식을 검증한다.
- 일반 테스트는 실제 Infrastructure 없이 계속 실행된다.
- 측정 환경의 한계와 운영 수치로 해석하면 안 되는 범위를 결과 문서에 명시한다.
