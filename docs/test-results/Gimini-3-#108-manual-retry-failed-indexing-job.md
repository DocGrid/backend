# #108 최종 실패 인덱싱 Job 수동 재처리 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-06 (Asia/Seoul)
- 대상 브랜치: `feature/108`
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 데이터베이스: 로컬 PostgreSQL 17.8 + pgvector 0.8.1 컨테이너
- 검증 범위: 전체 회귀 Test와 수동 재처리 전용 단위·Controller·PostgreSQL 통합 Test
- 최종 결과: 653개 통과, 실패·오류·Skip 0개

Test Class별 격리 Schema와 Flyway Migration을 사용했고, 기존 개발 데이터는 변경하지 않았다. DB 접속
정보와 인증 값은 실행 Process 환경변수로만 주입했으며 실제 값은 기록하지 않는다.

## 2. 전체 회귀 검증

실행 명령의 환경 값은 Placeholder로 대체한다.

```bash
DB_PORT='<local-test-port>' \
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
MINIO_ENDPOINT='<local-test-endpoint>' \
MINIO_ACCESS_KEY='<local-test-value>' \
MINIO_SECRET_KEY='<local-test-value>' \
MINIO_BUCKET='<local-test-bucket>' \
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL
tests=653 failures=0 errors=0 skipped=0
```

수동 재처리 추가 전 기준 Test 수는 646개였고, 이번 작업으로 단위 13개, 통합 8개, Controller 7개가
추가되어 653개가 됐다.

## 3. 단위 검증

`EmbeddingJobManualRetryServiceTest` 13개 통과.

| 시나리오 | 기대 | 결과 |
|---|---|---|
| Chunk가 있는 최종 실패 Job | `CHUNKED` 재개, 문서 `INDEXING` | 통과 |
| Chunk가 없는 최종 실패 Job | `UPLOADED` 재개 | 통과 |
| 재처리 후 소유권 | Worker·Token·Lease·`failed_at` 모두 `null` | 통과 |
| 재시도 이력 | `retry_count = 3` 유지, 잔여 재시도 없음 | 통과 |
| 감사 Event | `MANUAL_RETRY` 1건, Claim Token 미포함 | 통과 |
| 이전 `INDEXED` Version 존재 | 문서 상태·현재 포인터 보존 | 통과 |
| Job 없음 | `EMBEDDING_JOB_NOT_FOUND` | 통과 |
| `PENDING` Job | `EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED` | 통과 |
| `PROCESSING` Job | `EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED` | 통과 |
| `INDEXED` Job | `EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED` | 통과 |
| 최신 Version 아님 | `EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID`, 상태 변경 없음 | 통과 |
| 삭제된 문서 | `EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID` | 통과 |
| 같은 Version에 살아 있는 Job | `EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID` | 통과 |
| Version이 `FAILED`가 아님 | `DOCUMENT_INDEXING_FAILURE_INCONSISTENT` | 통과 |

## 4. Controller 계약 검증

`IndexingJobAdminControllerTest`의 수동 재처리 Test 7개 통과.

| 시나리오 | 기대 | 결과 |
|---|---|---|
| ADMIN 정상 요청 | 200, 재개 지점 포함, `claimToken`·`errorMessage` 미노출 | 통과 |
| `jobId = 0` | 400 `COMMON-002` | 통과 |
| Job 없음 | 404 `EMBEDDING-JOB-001` | 통과 |
| 최종 실패 Job 아님 | 409 `EMBEDDING-JOB-008` | 통과 |
| 대상 조건 불충족 | 409 `EMBEDDING-JOB-009` | 통과 |
| 종료 데이터 불일치 | 500 `DOCUMENT-INDEXING-004` | 통과 |
| USER 권한·미인증 | 403 | 통과 |

## 5. PostgreSQL 통합 검증

실행:

```bash
DB_PORT='<local-test-port>' \
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
MINIO_ENDPOINT='<local-test-endpoint>' \
MINIO_ACCESS_KEY='<local-test-value>' \
MINIO_SECRET_KEY='<local-test-value>' \
MINIO_BUCKET='<local-test-bucket>' \
./gradlew test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.EmbeddingJobManualRetryIntegrationTest'
```

결과:

```text
tests=8 failures=0 errors=0 skipped=0
```

### 5.1 재현 절차

각 Test는 격리 Schema에 다음 상태를 직접 구성한 뒤 실제 Service Transaction을 호출한다.

```text
embedding_jobs        status=FAILED, retry_count=3, max_retry_count=3,
                      locked_by_worker_id / claim_token / lock_expires_at / failed_at 존재
embedding_job_attempts status=FAILED (attempt_no=1)
indexing_events       FAILED 1건
document_versions     status=FAILED
embeddings            status=STALE (대상 Version)
documents             status=FAILED 또는 이전 INDEXED Version 보유
```

### 5.2 시나리오별 결과

| 시나리오 | 확인 항목 | 결과 |
|---|---|---|
| 최종 실패 Job 재처리 | `status=PENDING`, 소유권 4개 Column과 `failed_at`·`next_retry_at` `null`, `findNextPendingForUpdate`가 즉시 해당 Job 반환 | 통과 |
| Chunk 유지·Embedding 정리 | Version `CHUNKED`, `document_chunks` 1건 유지, 대상 Version `embeddings` 0건, 문서 `INDEXING` | 통과 |
| Chunk 없는 Job | Version `UPLOADED` | 통과 |
| 감사 이력 | `retry_count=3`, Attempt 1건 `FAILED` 유지, `FAILED` Event 1건 유지, `MANUAL_RETRY` Event 1건 추가, metadata에 Claim Token 없음 | 통과 |
| 이전 검색 Version 보호 | 문서 `INDEXED` 유지, `current_version_id`가 이전 Version, 이전 Version Embedding `ACTIVE`, 재처리 전후 Vector 검색 결과가 모두 `이전 검색 본문` | 통과 |
| 동시 요청 2건 | 커밋 1건, 나머지 1건 `EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED`, `MANUAL_RETRY` Event 정확히 1건, 대상 Embedding 0건 | 통과 |
| 자동 재시도 예정 Job | 409로 거부, `next_retry_at` 유지, Version `FAILED` 유지, `MANUAL_RETRY` Event 0건 | 통과 |
| 최신 Version 아님 | 409로 거부, Job `FAILED` 유지, 대상 Embedding 1건 그대로 유지 | 통과 |

## 6. Swagger 수동 검증

이번 작업에서는 Swagger 수동 검증을 수행하지 않았다. 수동 재처리 API는 `hasRole("ADMIN")`으로 보호되며
검증하려면 ADMIN 계정 생성과 JWT 발급, 그리고 자동 재시도를 모두 소진한 최종 실패 Job을 실제로
만들어야 한다. 이 사전 상태는 현재 로컬에서 실제 문서 업로드부터 Worker 실행까지 전 구간을 돌려야
재현할 수 있어, 예정된 로컬 전체 관통 E2E 작업에서 함께 수행하는 것이 적절하다.

대체 검증으로 다음 두 계층을 사용했다.

- HTTP 계약: `@WebMvcTest` 기반 Controller Test로 상태 코드, 응답 필드, 민감 정보 미노출, ADMIN 권한
  차단을 확인
- 실제 DB 동작: 실제 PostgreSQL 통합 Test로 상태 전이 원자성, 검색 보호, 동시 요청 수렴을 확인

## 7. 결론

- 최종 실패 Job만 수동 재처리 가능하고 나머지 상태는 모두 거부한다.
- 현재 검색 가능한 Version은 재처리 전후 동일한 검색 결과를 유지한다.
- Attempt와 재시도 감사 이력을 삭제하지 않는다.
- 소유권 정보가 초기화되어 과거 Claim Token으로는 후속 단계를 수행할 수 없다.
- 동시 재처리 요청은 하나의 상태 전이로 수렴한다.
- 전체 653개 Test가 실패 없이 통과한다.
