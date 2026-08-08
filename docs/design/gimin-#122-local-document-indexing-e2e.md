# Issue #122 실제 문서 임베딩 및 Vector 저장 전체 관통 검증 상세 설계

closes #122

## 1. 배경과 목적

문서 업로드, PDF·DOCX 파싱, Worker Polling, BGE-M3 Batch 임베딩, pgvector 저장, Version 완료와
검색 전환은 각 계층과 PostgreSQL 통합 Test에서 검증됐다. 그러나 실제 PostgreSQL 17, MinIO와
BGE-M3 서버를 동시에 연결해 HTTP 업로드부터 Vector 검색까지 한 실행으로 검증하는 경계는 없다.

이번 작업은 제품 기능을 확장하지 않는다. 실제 로컬 인프라를 사용하는 전용 E2E Test와 실행 Task를
추가해 다음 연결 계약을 고정한다.

```text
HTTP 업로드
→ MinIO 원본 저장
→ Embedding Job PENDING
→ Worker 등록·Claim·Attempt
→ PDF/DOCX Parsing·Chunk 저장
→ 실제 BGE-M3 Batch Embedding
→ pgvector vector(1024) 저장
→ Version INDEXED·current_version 전환
→ Query Embedding·Cosine 검색
```

### 1.1 성공 기준

- 실제 PDF와 DOCX가 HTTP로 업로드되고 MinIO Object가 존재한다.
- Worker가 별도 관리자 명령 없이 Job을 자동 실행한다.
- 모든 Chunk에 동일한 활성 모델의 1024차원 Vector가 한 번씩 저장된다.
- Version과 Job이 `INDEXED`, Document가 `INDEXED`로 수렴하고 현재 Version이 전환된다.
- 실제 Query Embedding과 pgvector `<=>` 검색에서 의미 관련 문서가 우선한다.
- Embedding Provider 연결 실패가 부분 Vector 없이 Retry 가능한 PENDING Job으로 수렴한다.
- 관리자 Job·Attempt·Event API를 실제 ADMIN JWT HTTP 요청으로 조회한다.
- 일반 `test`와 외부 인프라 E2E의 실행 경계가 분리된다.

## 2. 범위

### 2.1 포함

- `local-e2e` JUnit Tag와 `localE2eTest` Gradle Task
- PostgreSQL 격리 Schema와 MinIO 임시 Bucket
- Test 실행 중 생성하는 PDF·DOCX Fixture
- 실제 HTTP 로그인·Multipart 문서 업로드
- 실제 Worker 자동 Polling·Pipeline 실행
- 실제 BGE-M3 단건·Batch HTTP 호출
- Chunk·Embedding·Version·Job·Event DB 불변식 검증
- 실제 Query Vector와 pgvector 검색 검증
- Provider 연결 실패와 지연 재시도 예약 검증
- 관리자 조회 API와 OpenAPI 노출 검증
- 실행 절차·환경·측정 결과 문서

### 2.2 기존 검증을 재사용하는 범위

다음 장애·동시성은 실제 구현의 전용 PostgreSQL/MinIO Test가 이미 결정적으로 검증하므로 전체 관통
Task에서 함께 실행하고 결과를 한 문서로 집계한다.

- 다중 Worker Claim 단일성
- Lease 갱신 중 복구 차단과 갱신 중단 후 단일 복구
- 같은 파일 동시 업로드 Object 단일성
- 최종 FAILED 수동 재처리와 이전 검색 Version 보호
- 잘못된 Claim Token 차단

### 2.3 제외

- 제품 API·Entity·Migration 변경
- OpenSQL 원격 환경 검증과 성능 Benchmark
- OCR과 스캔 PDF 처리
- MCP·RAG 동작 변경
- Docker Image 또는 BGE-M3 Model 배포 자동화
- 운영 Secret, 라이선스와 설치 번들 저장소 반입

## 3. 실행 경계

### 3.1 일반 Test

기존 `test` Task는 `local-e2e` Tag를 제외한다. 외부 인프라와 모델 다운로드 여부가 CI 및 일반 개발
회귀 검증을 불안정하게 만들지 않게 한다.

```text
./gradlew test
```

### 3.2 로컬 전체 관통 Test

새 `localE2eTest` Task는 `local-e2e` Tag만 실행한다.

```text
docker compose up -d postgres minio embedding-server
./gradlew localE2eTest
```

Task는 인프라를 암묵적으로 기동하거나 종료하지 않는다. Test가 개발자의 기존 Container 상태를
변경하지 않고, 연결 실패를 명확한 사전 조건 오류로 드러내게 한다.

## 4. 격리 전략

### 4.1 PostgreSQL

- Test Class별 고유 Schema를 사용한다.
- Flyway 전체 Migration과 BGE-M3 Seed를 적용한다.
- 기존 `public` 또는 개발 Schema의 데이터를 읽거나 변경하지 않는다.
- Context 종료 시 Test Schema를 삭제한다.

### 4.2 MinIO

- 실행마다 UUID 기반 Bucket을 생성한다.
- 업로드된 Object의 Bucket·Key·Size·Content-Type을 검증한다.
- 종료 시 Object를 먼저 삭제한 뒤 Bucket을 삭제한다.

### 4.3 Embedding Server

- 성공 경로는 `EMBEDDING_SERVER_URL` 또는 기본 `http://localhost:8000`의 실제 서버를 사용한다.
- 장애 경로는 연결되지 않는 Test 전용 Endpoint를 사용해 Provider 연결 실패를 재현한다.
- 실제 운영 Endpoint나 인증 정보는 문서와 Test Source에 기록하지 않는다.

## 5. 성공 경로 시나리오

### 5.1 준비

1. Test Schema에 Flyway를 적용한다.
2. 임시 MinIO Bucket을 생성한다.
3. Worker가 Application Ready Event에서 등록될 때까지 기다린다.
4. Seed ADMIN 계정으로 실제 `/auth/login`을 호출해 JWT를 받는다.

### 5.2 업로드와 자동 실행

1. Text Layer가 있는 PDF와 제목·본문이 있는 DOCX를 Memory에서 생성한다.
2. `/api/documents`에 실제 Multipart HTTP 요청을 보낸다.
3. `201`, Document·Version·FileObject·Embedding Job ID와 `PENDING`을 확인한다.
4. MinIO Object가 실제 저장됐는지 확인한다.
5. Worker가 Claim하고 Pipeline을 완료할 때까지 제한 시간 안에서 DB 상태를 Polling한다.

### 5.3 DB 불변식

각 Document에 대해 다음을 검증한다.

```text
documents.status = INDEXED
documents.current_version_id = 업로드 Version
document_versions.status = INDEXED
embedding_jobs.status = INDEXED
embedding_job_attempts.status = SUCCESS
COUNT(document_chunks) > 0
COUNT(embeddings) = COUNT(document_chunks)
MIN/MAX(vector_dims(vector)) = 1024
COUNT(DISTINCT chunk_id) = COUNT(embeddings)
embeddings.status = ACTIVE
```

`INDEXED`, `EMBEDDING_STARTED`, `EMBEDDED` 등 실행 Event가 Job별로 정확한 순서를 이루는지도 확인한다.

### 5.4 실제 의미 검색

1. 실제 BGE-M3 단건 API로 PDF 주제와 가까운 Query Vector를 만든다.
2. 활성 BGE-M3 Model ID와 업로드한 두 Document ID를 허용 목록으로 전달한다.
3. pgvector `<=>` Top-K 결과의 첫 문서가 관련 PDF인지 확인한다.
4. 거리 값이 유한하고 관련 문서가 비관련 문서보다 가깝다는 것을 확인한다.

## 6. Provider 장애 경로

별도 Test Context가 같은 실제 PostgreSQL과 MinIO를 사용하되 Embedding Endpoint만 연결 불가능한 주소로
지정한다.

1. TXT 문서를 실제 HTTP로 업로드한다.
2. Worker가 Parsing·Chunk 저장 후 Batch Embedding 연결에 실패한다.
3. Attempt가 `FAILED`, Job이 Retry Count 1인 `PENDING`으로 돌아간다.
4. `next_retry_at`이 존재하고 Provider 실패·Retry Event가 각각 한 번 저장된다.
5. Chunk는 유지되고 Embedding은 0건이어서 부분 저장이 없음을 확인한다.
6. 재시도 지연을 충분히 길게 설정해 같은 Test 안에서 두 번째 Claim이 발생하지 않게 한다.

실제 Provider 재기동 후 재개 성공은 성공 경로와 기존 Worker 재개 Test의 조합으로 검증한다. Container
중지·기동을 JUnit이 직접 수행하지 않아 개발자의 Docker 상태를 침범하지 않는다.

## 7. 관리자 HTTP·Swagger 검증

성공한 Job을 대상으로 실제 ADMIN JWT를 사용한다.

- `GET /admin/indexing-jobs?documentId={id}&page=0&size=20` → `200`
- `GET /admin/indexing-jobs/{jobId}` → `200`
- `GET /admin/indexing-jobs/{jobId}/attempts?page=0&size=20` → `200`
- `GET /admin/indexing-jobs/{jobId}/events?page=0&size=20` → `200`
- `GET /v3/api-docs` → 네 Operation 노출

Page Metadata, INDEXED 상태, Attempt·Event 존재를 확인하고 JSON 전체에 `claimToken`, `errorMessage`,
`metadataJson`이 없는지 검증한다. 이는 PR #121 리뷰에서 남은 실제 HTTP 결과를 함께 보완한다.

## 8. Test Fixture 구조

### 8.1 `LocalE2eDocumentFactory`

- PDFBox로 Text PDF 생성
- Apache POI로 Heading·본문 DOCX 생성
- Filename과 Content-Type을 보존하는 `ByteArrayResource` 생성

### 8.2 `LocalE2eMinioBucket`

- Bucket 생성·존재 확인
- Object Stat과 목록 조회
- Object·Bucket 정리

Fixture는 `src/test`에만 존재하고 제품 Bean을 대체하지 않는다.

## 9. 실패 진단

| 실패 지점 | 진단 기준 |
|---|---|
| PostgreSQL | 연결 주소, Flyway Schema, pgvector Extension |
| MinIO | Health, Bucket 생성, Object Stat |
| BGE-M3 | `/health`, Model명, Batch 응답 수, Vector 차원 |
| Worker | Worker 상태, Job 상태, Attempt와 마지막 Event |
| Completion | Chunk·Embedding 수, Vector 차원, 현재 Version |
| Search | Query Vector 차원, 활성 Model, 허용 Document ID, 거리 |

Test Timeout 시 Job·Version·Attempt·Event Snapshot을 오류 메시지에 포함하되 Claim Token과 내부 Secret은
출력하지 않는다.

## 10. 커밋 분할

1. `docs: #122 로컬 전체 관통 E2E 설계 문서 추가`
2. `build: #122 로컬 전체 관통 E2E 실행 태스크 추가`
3. `test: #122 실제 문서와 MinIO E2E Fixture 추가`
4. `test: #122 업로드부터 Vector 검색까지 전체 관통 검증 추가`
5. `docs: #122 로컬 전체 관통 검증 결과 기록`

## 11. 완료 조건

- `./gradlew test`가 외부 인프라 E2E 없이 기존 회귀 검증을 통과한다.
- `./gradlew localE2eTest`가 실제 PostgreSQL·MinIO·BGE-M3 연결로 통과한다.
- PDF·DOCX 두 문서의 Chunk와 1024차원 Vector가 완전하게 저장된다.
- Version 완료, 현재 검색 Version 전환과 실제 의미 검색이 통과한다.
- Provider 장애가 부분 저장 없이 지연 재시도로 수렴한다.
- 관리자 조회 네 API의 실제 인증 HTTP와 민감정보 비노출이 통과한다.
- 실행 환경, 명령, Test 수, 처리 시간과 결과가 `docs/test-results/`에 기록된다.
