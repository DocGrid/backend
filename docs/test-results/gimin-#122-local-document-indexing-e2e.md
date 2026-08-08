# Issue #122 실제 문서 임베딩 및 Vector 저장 전체 관통 검증 결과

## 1. 결과 요약

2026-08-08 로컬 환경에서 실제 PostgreSQL 17, pgvector 0.8.1, MinIO와 BGE-M3를 연결해 문서
업로드부터 Vector 검색까지 전체 경로를 검증했다.

```text
HTTP Multipart 업로드
→ MinIO 원본 저장
→ Worker 자동 Claim·Attempt
→ PDF·DOCX Parsing·Chunk 저장
→ BGE-M3 Batch Embedding
→ vector(1024) 저장
→ Version INDEXED·current_version 전환
→ BGE-M3 Query Embedding·pgvector Cosine 검색
```

전체 실행 결과는 **24 tests, 24 passed, 0 failed**다. 외부 인프라 E2E는 일반 `test` Task와
분리돼 있으며 실행마다 Test Schema와 MinIO Bucket을 생성한 뒤 제거했다.

## 2. 검증 환경

| 항목 | 실제 값 |
|---|---|
| PostgreSQL | 17.8 |
| pgvector | 0.8.1 |
| PostgreSQL Image | `pgvector/pgvector:0.8.1-pg17` |
| Object Storage | 로컬 MinIO Container |
| Embedding Provider | `BAAI/bge-m3` 실제 HTTP Server |
| Dense Vector 차원 | 1024 |
| Application | Java 17, Spring Boot 3.5.16 |

민감한 DB·MinIO·JWT 값은 결과 문서에 기록하지 않았다. 실행자는 `.env` 또는 환경 변수로 주입하며,
Tmax 설치 번들·라이선스는 이 검증과 저장소에 사용하지 않았다.

## 3. 실제 실행 결과

### 3.1 로컬 전체 관통 E2E

```bash
DB_SSLMODE=disable ./gradlew localE2eTest
```

| Test Suite | 건수 | 실행 시간 | 결과 |
|---|---:|---:|---|
| 실제 문서 인덱싱 로컬 전체 관통 E2E | 1 | 3.279s | PASS |
| Embedding Provider 장애 로컬 전체 관통 E2E | 1 | 0.338s | PASS |

검증 내용:

- 실제 ADMIN 로그인과 PDF·DOCX Multipart 업로드가 각각 `201`을 반환했다.
- DB에 기록한 Bucket·Object Key로 MinIO Object를 조회해 원본 크기와 Content-Type이 일치했다.
- Worker가 별도 관리자 호출 없이 두 Job을 자동 Claim하고 완료했다.
- PDF Page와 DOCX Section Metadata가 Chunk에 보존됐다.
- 각 문서에서 `COUNT(embeddings) = COUNT(document_chunks)`가 성립했다.
- 모든 활성 Embedding의 `vector_dims(vector)`가 1024였다.
- Query Embedding도 1024차원 유한값이며 pgvector 검색에서 관련 PDF가 비관련 DOCX보다 먼저 반환됐다.
- Job 목록·상세·Attempt·Event 관리자 API를 실제 ADMIN JWT로 호출해 모두 `200`을 확인했다.
- 관리자 JSON에 `claimToken`, `errorMessage`, `metadataJson`이 노출되지 않았다.
- `/v3/api-docs`에 관리자 조회 네 경로가 모두 노출됐다.
- Provider 연결 실패는 Chunk를 유지하고 Embedding 0건인 상태에서 Retry Count 1의 `PENDING`으로
  수렴했다.
- 장애 Attempt는 `FAILED`, Job 오류 코드는 `EMBEDDING_PROVIDER_UNAVAILABLE`이며
  `EMBEDDING_FAILED`·`RETRY` Event가 각각 한 번 기록됐다.

### 3.2 Worker Claim·Lease 정합성

```bash
DB_SSLMODE=disable ./gradlew claimConcurrencyTest \
  --tests com.opensource.docgrid.domain.worker.integration.WorkerOrchestrationIntegrationTest
```

| Test Suite | 건수 | 실행 시간 | 결과 |
|---|---:|---:|---|
| Worker 오케스트레이션 PostgreSQL 통합 테스트 | 4 | 0.862s | PASS |

- 여러 Poller의 단일 Claim
- 실행 Slot보다 많은 Job의 선점 방지
- Lease 갱신 중 만료 복구 차단
- 갱신 중단 뒤 단일 복구

### 3.3 같은 파일 동시 업로드

```bash
DB_SSLMODE=disable JWT_SECRET=<test-only-value> ./gradlew minioIntegrationTest \
  --tests com.opensource.docgrid.domain.document.integration.minio.MinioUploadConcurrencyIntegrationTest
```

| Test Suite | 건수 | 실행 시간 | 결과 |
|---|---:|---:|---|
| 실제 MinIO 업로드 동시성 통합 테스트 | 2 | 0.619s | PASS |

같은 파일의 신규 문서 동시 업로드와 같은 문서의 새 Version 동시 업로드에서 DB FileObject와 실제
MinIO Object가 하나로 수렴하고 패자 후보 Object가 제거됐다.

### 3.4 Chunk·Embedding·수동 재처리 원자성

```bash
DB_SSLMODE=disable ./gradlew test \
  --tests com.opensource.docgrid.domain.embedding.integration.EmbeddingJobManualRetryIntegrationTest \
  --tests com.opensource.docgrid.domain.document.integration.DocumentChunkingIntegrationTest \
  --tests com.opensource.docgrid.domain.embedding.integration.DocumentEmbeddingIntegrationTest
```

| Test Suite | 건수 | 실행 시간 | 결과 |
|---|---:|---:|---|
| Document Chunking PostgreSQL 통합 테스트 | 5 | 0.820s | PASS |
| Document Embedding PostgreSQL 통합 테스트 | 3 | 0.109s | PASS |
| Embedding Job 수동 재처리 PostgreSQL 통합 테스트 | 8 | 0.305s | PASS |

소유권·Claim Token 검증, Chunk 재개, Embedding 원자 저장, 최종 실패 Job 수동 재처리와 이전 검색
Version 보호를 확인했다.

## 4. 관찰 사항

- 실제 업로드가 생성하는 첫 Event는 `LOCKED`다. `JOB_CREATED` enum은 현재 업로드 접수 경로에서
  기록되지 않으므로 실행 순서는 `LOCKED → PARSE_STARTED → CHUNKED → EMBEDDING_STARTED → INDEXED`로
  검증했다.
- Worker가 Provider 오류를 외부 Client 코드인 `EMBEDDING_SERVER_UNAVAILABLE`로 그대로 저장하지 않고
  운영 실패 분류인 `EMBEDDING_PROVIDER_UNAVAILABLE`로 정규화하는 것을 실제 장애 경로에서 확인했다.
- 외부 인프라가 없는 CI·일반 개발 환경은 `local-e2e` Tag를 제외하므로 기존 `./gradlew test` 실행
  안정성에 영향을 받지 않는다.

## 5. 판정

Issue #122 완료 조건을 충족했다.

- 실제 PDF·DOCX 원본 저장과 Parsing: PASS
- Worker 자동 Polling·Claim·Lease·Pipeline: PASS
- 실제 BGE-M3 Batch·Query Embedding: PASS
- pgvector `vector(1024)` 완전 저장과 Cosine 검색: PASS
- 완료·재시도·수동 복구·동시성 정합성: PASS
- 관리자 인증 HTTP·OpenAPI·민감정보 제거: PASS

공식 OpenSQL 환경의 호환성·성능 판정은 별도 원격 검증 작업에서 수행한다.
