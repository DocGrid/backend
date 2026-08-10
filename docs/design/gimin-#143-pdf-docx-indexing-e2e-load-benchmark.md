# PDF·DOCX 전체 인덱싱 E2E 부하 Benchmark 설계

## 1. 배경

자동 Worker 전체 인덱싱 처리량 Benchmark는 실제 PostgreSQL 17, MinIO와 `BAAI/bge-m3`에서
TXT 16·32문서의 기준선을 제공한다. 실제 PDF·DOCX는 소수 문서의 기능 E2E로 Parser와 Metadata를
검증했지만, 여러 문서를 동시에 접수했을 때의 처리량과 전체 Pipeline 데이터 완전성은 측정하지 않았다.

이번 작업은 실제 Text Layer PDF와 OOXML DOCX를 50·100문서 규모로 섞어 다음 질문에 답한다.

1. Parser가 다른 PDF·DOCX 혼합 부하에서 전체 인덱싱 처리량과 지연은 어느 수준인가?
2. 형식별 Upload·Queue·처리·전체 지연에 의미 있는 차이가 있는가?
3. 큰 Queue를 모두 소진한 뒤 페이지·Section Metadata와 Vector 저장 불변식이 유지되는가?

## 2. 범위

### 2.1 포함

- Memory에서 생성하는 실제 Text Layer PDF와 OOXML DOCX Binary
- 실제 인증과 Multipart HTTP 업로드
- 실제 MinIO Object 저장과 Content-Type·크기 검증
- 자동 Polling, Claim, Attempt, Parsing, Chunk 저장과 Lease 갱신
- 실제 BGE-M3 Batch Embedding과 PostgreSQL `vector(1024)` 저장
- 전체·PDF·DOCX별 처리량과 Upload·Queue·처리·E2E 지연
- Document·Version·Job·Attempt·Event·현재 검색 Version 정합성 검증
- PDF 페이지와 DOCX Section Metadata 보존 검증
- Git 제외 JSON 원본 결과와 실행 결과 Markdown 기록
- 일반 테스트와 분리한 전용 Gradle Task

### 2.2 제외

- OCR과 스캔 PDF
- 구형 `.doc`와 HWP
- 운영 Admission Control과 Rate Limit
- 공식 OpenSQL 원격 장비의 절대 성능 판정
- 제품 API·Entity·Migration 변경

## 3. Workload 계약

### 3.1 문서 Fixture

- PDF는 두 페이지 Text Layer를 포함하고 페이지별 고유 주제 문구를 가진다.
- DOCX는 제목과 두 개 이상의 Heading·본문 Section을 포함한다.
- 문서별 고유 식별 문구 외에 형식별 본문 길이와 구조를 동일하게 유지한다.
- 파일 이름과 제목은 Profile, 반복, 형식과 순번을 포함해 충돌을 차단한다.
- PDF와 DOCX는 각 Profile에서 같은 개수로 섞는다.

PDF는 PDFBox, DOCX는 Apache POI로 생성한다. 저장소 Fixture Binary를 추가하지 않고도 실제 Parser
경계를 통과하며, PDF는 OCR이 필요 없는 Text Layer만 검증한다.

### 3.2 Profile

| 구분 | 전체 문서 | PDF | DOCX | 반복 | 통계 포함 |
|---|---:|---:|---:|---:|---|
| Warm-up | 4 | 2 | 2 | 1 | 제외 |
| 중간 부하 | 50 | 25 | 25 | 2 | 포함 |
| 큰 부하 | 100 | 50 | 50 | 2 | 포함 |

본 측정 대상은 총 300문서다. 문서 수와 반복은 `document.indexing.e2e.load.*` System Property로
줄여 Smoke Test를 실행할 수 있다. Worker 최대 동시성은 같은 장비에서 문서 수 변화만 비교하도록
의도적으로 `2`에 고정한다. Embedding Batch Size는 제품 설정을 사용하며 두 값 모두 결과 환경 지문에
기록한다.

### 3.3 측정 순서

1. Warm-up PDF·DOCX를 업로드하고 모두 `INDEXED`가 될 때까지 기다린다.
2. Profile 시작 시각부터 여러 Uploader Thread로 PDF·DOCX를 교대로 접수한다.
3. 각 Upload 응답 시각과 마지막 Upload 완료 시각을 기록한다.
4. 자동 Worker가 Profile의 모든 Job을 `INDEXED`로 전환할 때까지 기다린다.
5. 형식별 Metadata와 전체 DB 불변식을 검증한 뒤 통계를 계산한다.
6. Profile 데이터를 정리하고 다음 반복을 시작한다.

Upload와 Worker 실행이 겹치는 실제 흐름을 유지한다. 모든 Uploader는 하나의 Profile 마감 시각을 공유해
개별 Future마다 Timeout이 누적되지 않게 하고, 실패 시 완료된 Run 결과를 JSON에 보존한다.

## 4. 지표 계약

전체와 PDF·DOCX별로 다음 지표를 기록한다.

| 지표 | 계산 |
|---|---|
| documents/s | 완료 문서 수 / Profile 전체 경과 시간 |
| documents/min | documents/s × 60 |
| chunks/s | 저장 Chunk 수 / Profile 전체 경과 시간 |
| embeddings/s | 저장 Embedding 수 / Profile 전체 경과 시간 |
| Upload 지연 | 개별 HTTP 요청 시작부터 응답까지 |
| Queue 대기 | Job `created_at`부터 `LOCKED` Event까지 |
| 실제 처리 | `LOCKED`부터 `INDEXED` Event까지 |
| 전체 Job 지연 | Job `created_at`부터 `INDEXED` Event까지 |

지연 분포는 선형 보간 p50·p95·p99와 max를 밀리초로 기록한다. Profile별 원본 Run과 Profile별
중앙값을 JSON에 함께 기록한다.

## 5. 정합성 계약

각 Profile은 다음 조건을 모두 검증한다.

- Upload 실패 0건, 대상 Job 전부 `INDEXED`
- 전체 Schema에 `PENDING`·`PROCESSING` 잔여 Job 없음
- Job별 `SUCCESS` Attempt 정확히 1개, 실패 Attempt와 Retry 0건
- Job별 `LOCKED`, `PARSE_STARTED`, `CHUNKED`, `EMBEDDING_STARTED`, `INDEXED` 순서 유지
- Document와 Version 모두 `INDEXED`
- `documents.current_version_id`가 측정 Version을 가리킴
- 문서별 Chunk 수가 1 이상이고 Embedding 수와 일치
- 같은 `(chunk_id, embedding_model_id)` 중복 없음
- 모든 Vector 차원 1024, Embedding 상태 `ACTIVE`
- PDF의 모든 Chunk가 유효한 페이지 번호를 보존
- DOCX의 모든 Chunk가 비어 있지 않은 Section 제목을 보존
- MinIO Object 수, Content-Type과 크기가 Upload 결과와 일치

하나라도 실패하면 성능 숫자를 유효한 결과로 취급하지 않고 Benchmark를 실패시킨다.

## 6. 환경과 결과 보존

시작 전에 다음 계약을 확인한다.

- PostgreSQL Server `17.x`
- pgvector `0.8.1`
- 실행 전용 Test Schema와 MinIO Bucket
- BGE Health와 Model명 `BAAI/bge-m3`
- Worker 최대 동시성과 Embedding Batch Size

Secret, JWT와 Object Storage Credential은 결과에 기록하지 않는다. 원본 JSON은 Git 제외 경로에 둔다.

```text
build/reports/document-indexing-e2e-load/document-indexing-e2e-load.json
```

실행 환경, Profile 중앙값, 해석과 한계는 `docs/test-results/`에 기록한다.

## 7. 실행 경계

일반 `./gradlew test`는 외부 Infrastructure에 의존하지 않는다. 전용 Task만 실제 PostgreSQL, MinIO와
BGE-M3를 요구한다.

```bash
docker compose up -d postgres minio embedding-server
./gradlew documentIndexingE2ELoadTest
```

작은 Smoke 실행은 다음과 같다.

```bash
./gradlew documentIndexingE2ELoadTest \
  -Ddocument.indexing.e2e.load.document-counts=4 \
  -Ddocument.indexing.e2e.load.repetitions=1
```

## 8. 실패 정책

- Infrastructure Health, DB·pgvector Version 또는 BGE Model 계약이 다르면 즉시 실패한다.
- Upload와 Worker 처리는 Profile 공통 제한 시간을 넘으면 상태 Snapshot과 함께 실패한다.
- 완료된 Run은 후속 진단을 위해 JSON에 보존하되 실패 Run은 중앙값에 포함하지 않는다.
- Profile별 식별자를 사용해 데이터를 분리하고, Class 종료 때 Schema와 Bucket을 정리한다.

## 9. 검증

- 형식 혼합과 형식별 통계 계약 단위 테스트
- 실제 Infrastructure를 사용하는 4문서 Smoke Benchmark
- 기본 50·100문서 Profile 각 2회 전체 Benchmark
- 기존 PDF·DOCX 로컬 E2E 회귀
- 전체 일반 Java 테스트

## 10. 커밋 분할

1. `docs: #143 PDF DOCX E2E 부하 Benchmark 설계`
2. `test: #143 문서 형식별 부하 통계 계약 추가`
3. `perf: #143 PDF DOCX 전체 인덱싱 부하 Benchmark 추가`
4. `build: #143 E2E 부하 전용 테스트 작업 추가`
5. `perf: #143 PDF DOCX 50 100문서 실측 결과 기록`

## 11. 완료 조건

- 실제 PDF·DOCX 혼합 Pipeline을 한 명령으로 50·100문서 규모에서 반복 측정할 수 있다.
- 본 측정 300문서가 모두 `INDEXED`로 수렴하고 실패·미완료·중복 Vector가 없다.
- 전체와 형식별 처리량 및 지연 분포가 구조화돼 기록된다.
- 페이지·Section Metadata와 Vector 1024차원 불변식이 모든 Profile에서 유지된다.
- 일반 테스트는 외부 Infrastructure 없이 계속 실행된다.
