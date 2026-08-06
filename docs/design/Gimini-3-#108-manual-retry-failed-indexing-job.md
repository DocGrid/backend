# Issue #108 최종 실패 인덱싱 Job 수동 재처리 상세 설계

closes #108

## 1. 문서 목적

이 문서는 이슈 [#108](https://github.com/DocGrid/backend/issues/108)의 구현 기준을 정의한다.

인덱싱 Job은 실패 유형이 재시도 가능하고 남은 횟수가 있을 때만 `PENDING` Queue로 재예약된다. 재시도
가능 횟수를 모두 소진했거나 재시도 불가 유형으로 종료된 Job은 `FAILED`로 종결되고, 대상 Document
Version은 `FAILED`, 그 Version의 Embedding Set은 `STALE`이 되어 검색에서 제외된다.

`FAILED` Job을 다시 처리할 경로는 현재 존재하지 않는다. Claim은 `PENDING`만, Lease 만료 복구는
`PROCESSING`만 후보로 삼기 때문에 어떤 자동 경로도 `FAILED` Job을 되살리지 않는다. 외부 Embedding
서버 장애나 일시적인 Storage 장애처럼 원인이 이미 해소된 뒤에도 같은 문서를 다시 인덱싱하려면 새
Version을 업로드하는 방법밖에 없다.

이 작업은 최종 실패로 종결된 Job만 관리자가 명시적으로 Queue에 되돌릴 수 있는 수동 재처리 경로를
추가한다.

### 1.1 성공 기준

- 최종 `FAILED` Job만 수동 재처리 대상이 된다.
- 처리 중이거나 자동 재시도가 예정된 Job은 명시적으로 거부한다.
- 최신 처리 대상 Version이 아니면 거부한다.
- 현재 검색 가능한 이전 Version과 `current_version` 포인터를 보존한다.
- 이전 Worker, Claim Token, Lease 등 소유권 정보를 초기화한다.
- Retry Count와 기존 Attempt 이력을 삭제하지 않는다.
- 수동 재처리를 새 상태 전이와 감사 Event로 남긴다.
- 기존 Chunk를 무조건 삭제하지 않는다.
- 중복 요청과 동시 요청이 하나의 상태 전이로 수렴한다.
- Lease 만료 복구 및 자동 재시도와 경합하지 않는다.
- 관리자 권한을 요구하고 Claim Token 등 민감 정보를 응답에 노출하지 않는다.

## 2. 범위

### 2.1 포함

- 최종 실패 Job 한 건을 다시 Queue에 넣는 관리자 API
- Job, Document Version, Document의 재처리 상태 전이
- 재개 지점 결정과 대상 Version Embedding 정리
- 수동 재처리 감사 Event 기록

### 2.2 제외

- 재처리 대상 Job 목록·상세 조회 API (관리자 인덱싱 조회 작업에서 진행)
- 자동 재시도 정책과 Backoff 계산 변경
- 여러 Job을 한 번에 재처리하는 Batch API
- 재처리 예약, 스케줄링, 자동 트리거
- OCR 등 실패 원인 자체를 해결하는 파싱 기능

## 3. 현재 구조 분석

### 3.1 상태 모델

`EmbeddingJobStatus`는 `PENDING`, `PROCESSING`, `INDEXED`, `FAILED`, `CANCELED`로 구성된다. 별도의
재시도 예약 상태는 없고, 자동 재시도가 예정된 Job은 `PENDING` + 미래의 `next_retry_at`으로 표현된다.
Claim Query는 `next_retry_at IS NULL OR next_retry_at <= :claimedAt` 조건을 사용하므로 예약 시각 전에는
후보가 되지 않는다.

### 3.2 최종 실패 시점의 데이터 상태

`IndexingFailureTransitionService`의 최종 실패 경로는 하나의 Transaction에서 다음을 수행한다.

1. 대상 Version의 `ACTIVE` Embedding을 모두 `STALE`로 전환
2. `document_versions.status`를 `FAILED`로 전환
3. 이전 `INDEXED` Version이 현재 검색 대상이면 Document를 그대로 두고, 아니면 `FAILED`로 전환
4. `embedding_jobs.status`를 `FAILED`로 전환하고 `failed_at`, 오류 Snapshot 기록
5. 단계 실패 Event와 `FAILED` Event를 같은 시각으로 append

`markFailed`는 `locked_by_worker_id`, `claim_token`, `locked_at`, `lock_expires_at`을 감사 목적으로
남긴다. `document_chunks`는 삭제하지 않는다.

### 3.3 재개 지점 계약

파이프라인 각 단계는 Version 상태로 재개 지점을 판단한다.

| Version 상태 | 동작 |
|---|---|
| `UPLOADED`, `PARSING` | 원본을 다시 읽어 파싱하고 Chunk Set 저장 |
| `CHUNKED` | 파싱을 생략하고 Embedding 생성 |
| `EMBEDDING` | 저장된 Embedding 수에 따라 재생 또는 재작업 |

Chunk Set 저장과 `CHUNKED` 전이는 같은 Transaction에서 일어나므로 Chunk가 존재하면 항상 완전한
Set이다. 반면 `CHUNKED` 상태에서 대상 Version·Model의 Embedding 행이 0이 아니면
`DOCUMENT_EMBEDDINGS_INCONSISTENT`로 차단된다. 따라서 최종 실패가 남긴 `STALE` Embedding을 정리하지
않으면 재처리 자체가 불가능하다.

### 3.4 검색 보호 장치

Vector 검색 Query는 `e.status = 'ACTIVE' AND d.status = 'INDEXED' AND d.current_version_id =
e.document_version_id` 조건을 사용한다. 실패한 Version의 Embedding은 `STALE`이므로 원래 검색에 노출될
수 없고, 이전 `INDEXED` Version은 `current_version_id`가 유지되는 한 계속 검색된다.

## 4. 설계

### 4.1 상태 전이 계약

```text
사전조건:  embedding_jobs.status = FAILED
           document_versions = 해당 문서의 최신 Version, status = FAILED
           documents.deleted_at IS NULL
           documents.status ∈ {UPLOADED, INDEXING, INDEXED, FAILED}
           같은 Version에 PENDING/PROCESSING Job 없음

전이:      Job:      FAILED  -> PENDING, next_retry_at = NULL
                     locked_by_worker_id, claim_token, locked_at, lock_expires_at, failed_at = NULL
                     retry_count, max_retry_count, error_code, error_message 보존
           Version:  FAILED  -> CHUNKED  (Chunk가 이미 있는 경우)
                             -> UPLOADED (Chunk가 없는 경우)
           Document: 이전 INDEXED Version이 현재 검색 대상이면 변경 없음
                     그 외에는 INDEXING
           Event:    MANUAL_RETRY (FAILED -> PENDING) 1건 append
           Attempt:  변경 없음
```

`retry_count`를 유지하므로 수동 재처리는 추가 실행 1회만 부여한다. 이번 실행이 다시 실패하면
`hasRemainingRetries()`가 거짓이 되어 자동 재시도 없이 즉시 최종 실패로 종결되고, 필요하면 관리자가
다시 수동 재처리를 요청한다. 이 선택은 재시도 이력을 지우지 않으면서 무한 자동 재시도를 만들지
않기 위한 것이다.

### 4.2 Chunk와 Embedding 처리 정책

- Chunk는 삭제하지 않는다. 존재하면 완전한 Set이므로 파싱을 생략하고 재사용한다.
- 대상 Version의 Embedding 행만 삭제한다. 최종 실패 시점에 이미 `STALE`이라 검색에 노출되지 않으며,
  남겨두면 Embedding 개수 불변식 검증에서 재처리가 차단된다.
- 다른 Version의 Chunk와 Embedding은 조회하지도 변경하지도 않는다.

실패한 실행이 남긴 Vector를 다시 `ACTIVE`로 되살리는 방식은 채택하지 않았다. 완료 검증 단계에서
실패한 경우 그 Vector Set이 실제로 불완전할 수 있고, 이를 판별하려면 완료 Transaction과 같은 수준의
검증을 재처리 경로에 중복 구현해야 하기 때문이다.

### 4.3 Transaction 경계와 잠금 순서

`EmbeddingJobManualRetryService`는 단일 `@Transactional` 경계에서 외부 I/O 없이 동작한다.

1. `findByIdForUpdate`로 Job 행을 잠근다.
2. Job 상태가 `FAILED`인지 확인한다.
3. Version, Document를 기존 경로와 같은 순서로 잠근다.
4. 재처리 대상 조건을 모두 검증한다.
5. 재개 지점을 정하고 대상 Version Embedding을 삭제한다.
6. Job, Version, Document 상태를 바꾸고 `MANUAL_RETRY` Event를 append한다.

Job 행 잠금이 Claim, 완료, 협력적 실패, Lease 복구와의 단일 직렬화 지점이다. Lease 복구는
`PROCESSING` + 만료 행만, Claim은 `PENDING` 행만 후보로 삼으므로 커밋 전에는 이 Transaction과 경합하지
않고, 커밋 후에는 정상 Claim 경로로 흡수된다.

### 4.4 API 계약

```text
POST /admin/indexing-jobs/{jobId}/retry
Request Body 없음, ADMIN 권한 필요

200 OK
{
  "success": true,
  "data": {
    "jobId": 10,
    "status": "PENDING",
    "documentId": 3,
    "documentVersionId": 5,
    "documentVersionStatus": "CHUNKED",
    "retryCount": 3,
    "maxRetryCount": 3,
    "requeuedAt": "2026-08-06T15:00:00"
  }
}
```

`/admin/**`은 `SecurityConfig`에서 이미 `hasRole("ADMIN")`으로 보호되므로 Security 설정은 변경하지
않는다. 응답에는 Claim Token, 실패 원인 상세, 내부 예외 정보를 포함하지 않는다.

## 5. 오류 케이스

| 상황 | HTTP | 코드 |
|---|---|---|
| Job 없음 | 404 | `EMBEDDING-JOB-001` |
| Job이 `PENDING`·`PROCESSING`·`INDEXED`·`CANCELED` (중복 요청 포함) | 409 | `EMBEDDING-JOB-008` |
| 최신 Version이 아님 | 409 | `EMBEDDING-JOB-009` |
| 삭제된 문서이거나 재처리 불가 문서 상태 | 409 | `EMBEDDING-JOB-009` |
| 같은 Version에 살아 있는 Job 존재 | 409 | `EMBEDDING-JOB-009` |
| Version이 `FAILED`가 아니거나 현재 Version 포인터 불일치 | 500 | `DOCUMENT-INDEXING-004` |
| Job ID가 양수가 아님 | 400 | `COMMON-002` |

중복 요청은 멱등 재생 대신 명시적 충돌로 처리한다. 현재 Schema에는 `PENDING` Job이 자동 재시도
예약인지 수동 재처리 결과인지 구분하는 식별자가 없어, 멱등 재생을 지원하려면 추가 Column이나 Event
조회가 필요하기 때문이다.

## 6. 테스트 설계

### 6.1 단위 테스트

`EmbeddingJobManualRetryServiceTest` (Mockito)

- Chunk 존재 시 `CHUNKED` 재개, 미존재 시 `UPLOADED` 재개
- 소유권 필드와 종료 시각 초기화, `retry_count` 보존
- Claim Token 없는 `MANUAL_RETRY` Event 기록
- 이전 `INDEXED` Version이 있을 때 문서 상태·포인터 보존
- Job 없음, `PENDING`·`PROCESSING`·`INDEXED` 거부
- 최신 Version 아님, 삭제된 문서, 살아 있는 Job 존재 거부
- Version이 `FAILED`가 아닐 때 불변식 오류

### 6.2 Controller 테스트

`IndexingJobAdminControllerTest` (`@WebMvcTest`)

- 정상 응답 필드와 민감 정보 미노출
- Job ID Validation
- 정의된 오류 코드와 HTTP 상태 매핑
- ADMIN 외 사용자와 미인증 요청 차단

### 6.3 통합 테스트

`EmbeddingJobManualRetryIntegrationTest` (`@Tag("integration")`, 실제 PostgreSQL)

- 소유권 초기화 후 즉시 Claim 후보가 되는지 확인
- Chunk 유지와 대상 Version Embedding 삭제
- Chunk 없는 Job의 `UPLOADED` 재개
- Attempt 이력·재시도 횟수 보존과 `MANUAL_RETRY` Event 1건
- 이전 `INDEXED` Version의 검색 결과와 현재 포인터 보존
- 동시 요청 2건이 전이 1회 + 충돌 1회로 수렴
- 자동 재시도 예정 Job 거부 시 예약 유지
- 최신 Version이 아닐 때 거부하고 기존 데이터 유지

## 7. 커밋 분할

1. `feat: #108 최종 실패 Job 수동 재처리 도메인 규칙 추가`
2. `feat: #108 수동 재처리 대상 Embedding 삭제 쿼리 추가`
3. `feat: #108 최종 실패 Job 수동 재처리 Command Service 구현`
4. `feat: #108 관리자 수동 재처리 API 추가`
5. `test: #108 수동 재처리 단위·Controller 테스트 추가`
6. `test: #108 수동 재처리 PostgreSQL 통합 테스트와 검증 결과 추가`
7. `docs: #108 최종 실패 Job 수동 재처리 설계 문서 추가`

## 8. 완료 조건

- 최종 `FAILED` Job만 수동 재처리 가능
- `PENDING`·`PROCESSING`·`INDEXED`·`CANCELED` 거부
- 현재 검색 가능한 Version 유지
- Attempt와 Retry 감사 이력 유지
- 소유권 정보 초기화
- 동시 재처리 요청이 하나의 상태 전이로 수렴
- 전체 회귀 테스트 통과

## 9. 참고

- Flyway 마이그레이션 없음. `indexing_events.event_type`은 CHECK 제약이 없는 `VARCHAR(30)`이라
  `MANUAL_RETRY` 값을 그대로 저장할 수 있다.
- `SecurityConfig` 변경 없음.
