# Issue #88 인덱싱 실패 종료 및 지연 재시도 상세 설계

closes #88

## 1. 목적

문서 인덱싱 성공 경로는 현재 Claim과 Attempt를 검증한 뒤 Job, Version, Document를 하나의
Transaction에서 `INDEXED`로 확정한다. 그러나 파싱, Embedding 생성 또는 완료 단계에서 오류가 발생하면
호출자에게 오류 응답만 반환되고 다음 상태가 남는다.

```text
EmbeddingJobAttempt = STARTED
EmbeddingJob = PROCESSING
DocumentVersion = PARSING 또는 EMBEDDING
lock_expires_at = 최초 Claim 시각 + 고정 Lease
```

이 상태에서는 같은 Job을 다시 Claim할 수 없고, 오류 원인과 시도 종료 시각도 기록되지 않는다. 기존
Schema와 Enum에는 `retry_count`, `max_retry_count`, `failed_at`, `error_code`, `error_message`,
`PARSE_FAILED`, `EMBEDDING_FAILED`, `RETRY`, `FAILED`가 이미 존재하지만 이를 연결하는 Use Case가 없다.

이 이슈의 목적은 현재 Claim을 소유한 Worker가 실패를 명시적으로 보고했을 때 실행 Attempt를 정확히
한 번 종료하고, 서버가 실패 유형과 남은 횟수를 기준으로 지연 재시도 또는 최종 실패를 결정하는 것이다.

## 2. 범위

### 2.1 포함 범위

- 관리자용 인덱싱 실패 종료 API
- Worker ID, Claim Token, Lease와 Attempt 실행 Context 검증
- 제한된 실패 유형과 서버 소유 재시도 정책
- Attempt `STARTED -> FAILED` 전이와 오류 원인·종료 시각·소요 시간 저장
- Job `PROCESSING -> PENDING` 지연 재시도 전이
- Job `PROCESSING -> FAILED` 최종 실패 전이
- `retry_count` 증가와 `next_retry_at` 계산
- 재시도 시 현재 Worker, Claim Token과 Lease 해제
- 최종 실패 시 Version `FAILED` 전이
- 검색 가능한 current Version 존재 여부에 따른 Document 상태 결정
- 최종 실패 Version의 `ACTIVE` Embedding `STALE` 전환
- 단계별 실패, 재시도와 최종 실패 이벤트 저장
- 동일 실패 요청의 멱등 재생
- 완료 요청과 실패 요청의 동시성 제어
- 실제 PostgreSQL의 실행 가능 시각, 행 잠금, Rollback 검증

### 2.2 제외 범위

- Lease 연장 API와 주기적인 Lease 갱신
- 만료 Lease 자동 회수
- DEAD Worker가 소유한 Job 복구
- Worker 자동 Polling Loop
- 관리자 수동 재시도·취소 API
- Retry Jitter
- Chunk 또는 Vector 단위 Checkpoint와 부분 재개
- 실패 이력 조회 Dashboard와 Metric

이번 기능은 유효한 Lease를 가진 Worker가 협력적으로 보고한 실패만 처리한다. Worker가 죽었거나 첫 실패
보고 전에 Lease가 만료된 경우는 후속 Lease Recovery 기능이 담당한다.

## 3. 핵심 결정

### 3.1 Worker가 재시도 여부를 직접 결정하지 않는다

요청에 `retryable: true`를 허용하면 호출자가 영구적인 데이터 오류를 무한히 재시도하거나 일시적인
인프라 오류를 즉시 종결할 수 있다. API는 제한된 `failureType`만 받고 서버 Enum이 재시도 가능 여부를
소유한다.

| failureType | 의미 | 재시도 |
| --- | --- | --- |
| `STORAGE_UNAVAILABLE` | 원본 Object Storage 연결·Timeout | 가능 |
| `DOCUMENT_CONTENT_INVALID` | 지원 불가 형식, 빈 본문, Decode 실패 | 불가 |
| `EMBEDDING_PROVIDER_UNAVAILABLE` | Embedding Provider 연결·Timeout | 가능 |
| `EMBEDDING_RESULT_INVALID` | Vector 개수·차원·값 불일치 | 불가 |
| `INDEXING_STATE_INCONSISTENT` | Job·Version·Chunk·Embedding 불변식 위반 | 불가 |
| `WORKER_INTERNAL_ERROR` | Worker 내부의 일시적인 실행 실패 | 가능 |

Enum 이름을 `error_code`에 저장한다. 사용자가 입력한 자유 문자열을 정책 결정에 사용하지 않는다.

### 3.2 `max_retry_count`는 최초 실행 이후 재시도 횟수다

`max_retry_count = 3`이면 최초 Attempt를 포함해 최대 네 번 실행할 수 있다.

```text
Attempt 1 실패 -> retry_count 1, 재시도 예약
Attempt 2 실패 -> retry_count 2, 재시도 예약
Attempt 3 실패 -> retry_count 3, 재시도 예약
Attempt 4 실패 -> Retry 소진, 최종 FAILED
```

영구 실패는 현재 `retry_count`와 관계없이 즉시 최종 종료한다.

### 3.3 Retry는 Scheduler가 아니라 Queue 실행 가능 시각으로 제어한다

`embedding_jobs.next_retry_at`을 추가하고 Claim 쿼리가 현재 시각 이후의 PENDING Job을 제외한다.

```sql
WHERE job.status = 'PENDING'
  AND (job.next_retry_at IS NULL OR job.next_retry_at <= :claimedAt)
ORDER BY job.priority DESC,
         job.created_at ASC,
         job.id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED
```

새 Job의 `next_retry_at`은 null이며 즉시 실행할 수 있다. Retry Job은 예약 시각이 지난 뒤 기존 Claim
API에서 자연스럽게 선택된다. 별도 Scheduler와 중복 Queue 이동 로직을 추가하지 않는다.

### 3.4 Version 상태를 유지해 기존 멱등 재개 규칙을 사용한다

재시도 시 Version을 `UPLOADED`로 되돌리지 않는다.

| 현재 Version 상태 | 저장 데이터 | 다음 Attempt 동작 |
| --- | --- | --- |
| `UPLOADED` | Chunk 없음 | 파싱 최초 실행 |
| `PARSING` | Chunk 없음 | 파싱 재개 |
| `CHUNKED` | 확정 Chunk Set | Embedding 최초 실행 |
| `EMBEDDING` | Embedding 없음 | Embedding 재실행 |
| `EMBEDDING` | 전체 Embedding Set | Embedding 결과 재생 후 완료 |

Chunk와 Embedding은 각 완료 Transaction에서 전체 Set을 원자 저장하므로 정상 흐름에는 부분 Set이 남지
않는다. 부분 Set은 기존 불변식 오류로 드러내며 이번 이슈에서 삭제하거나 보정하지 않는다.

### 3.5 Attempt 응답만 고정해 장기 멱등 재생을 보장한다

실패 요청 이후 Job은 PENDING 재예약, 새 Claim, 성공 완료 등으로 계속 바뀔 수 있다. 따라서 실패 응답에
현재 Job 상태나 다음 재시도 시각을 포함하면 같은 요청의 재생 결과가 최초 응답과 달라진다.

응답은 종료된 Attempt에 저장된 값만 포함한다.

```text
jobId
attemptId
attemptNo
attemptStatus = FAILED
failureType
failedAt
durationMs
```

Retry 결정은 Job 조회와 이벤트에서 확인하며 실패 응답 계약에는 포함하지 않는다.

## 4. API 계약

### 4.1 Endpoint

```http
POST /admin/indexing-jobs/{jobId}/attempts/{attemptId}/fail
Content-Type: application/json
```

### 4.2 요청

```json
{
  "workerId": 7,
  "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab",
  "failureType": "EMBEDDING_PROVIDER_UNAVAILABLE",
  "errorMessage": "Embedding provider request timed out"
}
```

| 필드 | 검증 |
| --- | --- |
| `workerId` | 필수, 양수 |
| `claimToken` | 필수, 36자 이하 canonical UUID |
| `failureType` | 필수 Enum |
| `errorMessage` | 필수, 공백 불가, 최대 2,000자 |

오류 메시지에는 Stack Trace, Authorization Header, 원본 문서 내용, Bucket/Object Key와 사용자 개인정보를
넣지 않는다. 서버 로그에도 Claim Token 전체와 오류 메시지 전문을 출력하지 않는다.

### 4.3 응답

```json
{
  "jobId": 10,
  "attemptId": 21,
  "attemptNo": 2,
  "attemptStatus": "FAILED",
  "failureType": "EMBEDDING_PROVIDER_UNAVAILABLE",
  "failedAt": "2026-08-03T10:30:00",
  "durationMs": 42031
}
```

최초 실패와 동일 요청 재생은 모두 `200 OK`다.

### 4.4 오류 응답

| 조건 | HTTP | ErrorCode |
| --- | --- | --- |
| Path 또는 Body 형식 오류 | 400 | `COMMON-002` |
| Job 없음 | 404 | `EMBEDDING-JOB-001` |
| Job 상태가 PROCESSING이 아님 | 409 | `EMBEDDING-JOB-002` |
| Worker 또는 Claim Token 불일치 | 409 | `EMBEDDING-JOB-003` |
| Lease 만료 | 409 | `EMBEDDING-JOB-004` |
| 소유권 데이터 불완전 | 500 | `EMBEDDING-JOB-005` |
| Attempt 실행 Context 불일치 | 409 | `EMBEDDING-JOB-006` |
| 같은 Attempt의 다른 실패 내용 | 409 | 신규 `EMBEDDING-JOB-007` |
| Version·Document 실패 데이터 모순 | 500 | 신규 `DOCUMENT-INDEXING-004` |

## 5. 데이터 모델

### 5.1 Migration

```sql
ALTER TABLE embedding_jobs
    ADD COLUMN next_retry_at TIMESTAMP;

CREATE INDEX idx_embedding_jobs_status_next_retry_at
    ON embedding_jobs (status, next_retry_at);
```

기존 PENDING Job은 null이므로 즉시 Claim 가능하다. Migration에서 기존 데이터의 의미를 바꾸지 않는다.

### 5.2 EmbeddingJob

추가 필드:

```java
private LocalDateTime nextRetryAt;
```

상태 전이 책임:

- `scheduleRetry(...)`
  - PROCESSING과 남은 Retry 횟수 검증
  - `retryCount` 증가
  - 상태를 PENDING으로 변경
  - `nextRetryAt`과 최근 오류 저장
  - Worker, Token, Lock 시작·만료 시각 제거
- `markFailed(...)`
  - PROCESSING만 최종 FAILED 허용
  - `failedAt`과 오류 저장
  - `nextRetryAt` 제거
- `claim(...)`
  - PENDING 상태에서 새 소유권 기록
  - 소비한 `nextRetryAt` 제거
- `markIndexed(...)`
  - 성공 상태 기록
  - Retry 과정의 최근 오류와 예약 시각 제거

`startedAt`은 Job 전체의 최초 처리 시작 시각이므로 재Claim에서도 유지한다. Attempt별 시작·실패 시각은
`embedding_job_attempts`가 보존한다.

### 5.3 EmbeddingJobAttempt

`markFailed(...)`는 `STARTED` 상태만 허용한다. 이미 SUCCESS 또는 FAILED인 Attempt의 값을 덮어쓰지
않는다. 멱등 재생 판단은 Entity 상태를 변경하기 전에 Service에서 수행한다.

## 6. 상태 전이

### 6.1 재시도 가능한 실패

```text
Attempt: STARTED -> FAILED
Job: PROCESSING -> PENDING
Version: 현재 단계 유지
Document: 변경 없음
Embedding: 변경 없음
```

처리 순서:

1. 하나의 `failedAt`을 microsecond 정밀도로 계산한다.
2. Attempt에 실패 유형, 안전한 메시지, 종료 시각과 소요 시간을 기록한다.
3. 현재 Retry 횟수로 Backoff를 계산한다.
4. Job Retry 횟수를 증가시키고 `nextRetryAt`을 저장한다.
5. Job의 현재 소유권 정보를 제거한다.
6. 단계별 실패 이벤트와 RETRY 이벤트를 같은 시각으로 저장한다.

### 6.2 영구 실패 또는 Retry 소진

```text
Attempt: STARTED -> FAILED
Job: PROCESSING -> FAILED
Version: UPLOADED/PARSING/CHUNKED/EMBEDDING -> FAILED
```

Document는 현재 검색 가능한 Version의 존재 여부로 결정한다.

| current Version | Document 처리 |
| --- | --- |
| 실패 대상과 동일한 최초 Version | Document FAILED |
| 이전 FAILED Version이고 검색 가능 Version 없음 | Document FAILED |
| 이전 INDEXED Version | Document INDEXED와 current 포인터 유지 |
| null 또는 그 밖의 중간 상태 | 내부 불변식 오류, 전체 Rollback |

대상 Version의 `ACTIVE` Embedding은 `STALE`로 바꾼다. Retry가 끝났으므로 재사용하지 않으며, 검색
쿼리의 current Version 조건뿐 아니라 Embedding 자체 상태에서도 검색 불가를 표현한다.

### 6.3 후속 성공

Retry 중 저장한 Job 오류 Snapshot은 Attempt 이력에 이미 보존돼 있다. Job이 이후 INDEXED로 완료되면
`errorCode`, `errorMessage`, `failedAt`, `nextRetryAt`을 제거해 현재 Job Snapshot을 성공 상태와 맞춘다.

## 7. Backoff

설정:

```yaml
indexing:
  worker:
    retry-initial-delay: ${INDEXING_WORKER_RETRY_INITIAL_DELAY:10s}
    retry-max-delay: ${INDEXING_WORKER_RETRY_MAX_DELAY:5m}
```

검증:

- 두 Duration 모두 양수
- 최대 지연은 초기 지연 이상

계산:

```text
delay = min(retryInitialDelay * 2 ^ currentRetryCount, retryMaxDelay)
nextRetryAt = failedAt + delay
```

곱셈 Overflow가 발생하지 않도록 최대 지연에 도달하면 즉시 계산을 중단한다. Jitter는 결정적인 테스트와
MVP 단순성을 위해 이번 범위에 넣지 않는다.

## 8. Transaction과 잠금

### 8.1 최초 실패

```text
Transaction 시작
-> Job SELECT FOR UPDATE
-> Attempt 조회 및 STARTED 검증
-> 현재 Worker, Claim Token, Lease 검증
-> Version SELECT FOR UPDATE
-> Document SELECT FOR UPDATE
-> 실패 유형과 Retry 잔여 횟수 판정
-> Attempt, Job, Version, Document, Embedding 상태 변경
-> IndexingEvent Insert
-> Commit
```

완료 흐름과 동일하게 `Job -> Version -> Document` 순서를 유지한다. Job 잠금이 완료, 실패, Retry와
Claim 세대 교체의 직렬화 지점이다.

### 8.2 완료와 실패 경쟁

| 먼저 커밋한 요청 | 나중 요청 |
| --- | --- |
| 완료: Job INDEXED | 실패는 PROCESSING이 아니므로 거부 |
| Retry: Job PENDING, Token 제거 | 과거 완료는 상태·소유권 오류로 거부 |
| 최종 실패: Job FAILED | 완료는 완료 불가 오류로 거부 |

한 요청이 상태를 일부 바꾼 뒤 다른 요청이 이어서 커밋할 수 없다.

### 8.3 Version 업로드와의 관계

최종 실패 Transaction이 Document 잠금을 해제하기 전에는 새 Version 업로드가 진행되지 않는다. 새
업로드는 Version이 FAILED이고 Document가 FAILED 또는 기존 INDEXED 상태인 일관된 결과만 관찰한다.

## 9. 멱등성

Job 잠금 뒤 Attempt를 먼저 조회한다. Attempt가 이미 FAILED이면 현재 Job 상태와 Lease를 다시 검증하지
않고 다음 저장 값을 확인한다.

- Attempt ID와 Job ID
- Worker ID
- Claim Token
- 저장된 failureType
- 저장된 errorMessage

모두 같으면 저장된 `failedAt`, `durationMs`로 응답한다. 최초 실패 후 Job이 새 Worker에게 Claim되거나
이미 INDEXED가 됐어도 과거 실패 응답을 안전하게 재생할 수 있다.

하나라도 다르면 같은 실행 이력을 다른 내용으로 덮으려는 요청이므로 409를 반환한다. 멱등 재생에서는
Retry 횟수, 예약 시각, 상태, 이벤트를 변경하지 않는다.

## 10. 이벤트

모든 이벤트는 Attempt 종료 시각과 같은 `occurredAt`을 사용한다.

### 10.1 단계별 실패 이벤트

| Version 상태 | 이벤트 |
| --- | --- |
| `UPLOADED`, `PARSING` | `PARSE_FAILED` |
| `CHUNKED`, `EMBEDDING` | `EMBEDDING_FAILED` |

오류 상세는 Job과 Attempt에 저장한다. 이벤트 메시지는 일반화하고 Metadata에는 다음 비민감 값만 넣는다.

```json
{
  "attemptId": 21,
  "attemptNo": 2,
  "failureType": "EMBEDDING_PROVIDER_UNAVAILABLE"
}
```

### 10.2 Retry 이벤트

- eventType: `RETRY`
- fromStatus: `PROCESSING`
- toStatus: `PENDING`
- Metadata: `retryCount`, `nextRetryAt`

### 10.3 최종 실패 이벤트

- eventType: `FAILED`
- fromStatus: `PROCESSING`
- toStatus: `FAILED`
- Metadata: `retryCount`, `maxRetryCount`

같은 Attempt의 멱등 재생은 이벤트를 추가하지 않는다.

## 11. 구성요소

### 11.1 신규

```text
src/main/java/com/opensource/docgrid/domain/embedding/
├── dto/request/FailDocumentIndexingRequest.java
├── dto/response/DocumentIndexingFailureResponse.java
├── enums/IndexingFailureType.java
└── service/command/DocumentIndexingFailureService.java

src/main/resources/db/migration/
└── V35__add_embedding_job_next_retry_at.sql
```

### 11.2 수정

| 파일 | 변경 |
| --- | --- |
| `EmbeddingJob` | 예약 시각과 Retry·최종 실패 전이 |
| `EmbeddingJobAttempt` | STARTED 전용 실패 Guard |
| `DocumentVersion` | 실패 가능 상태 Guard |
| `EmbeddingJobRepository` | 실행 가능 시각 기반 Claim |
| `EmbeddingRepository` | 최종 실패 Version ACTIVE Set 비활성화 계약 설명 |
| `IndexingWorkerProperties` | Retry Backoff 설정과 검증 |
| `EmbeddingJobClaimService` | Claim 기준 시각 Repository 전달 |
| `IndexingJobAdminController` | 실패 API와 Swagger 계약 |
| `ErrorCode` | 실패 내용 충돌과 데이터 불일치 코드 |
| `application.yml` | Retry 설정 환경 변수 |

## 12. 테스트 전략

### 12.1 단위 테스트

- Retry 설정 기본값과 양수·상하한 검증
- Backoff 10초, 20초, 40초와 최대 지연 제한
- PENDING이 아닌 Job의 Retry 예약 거부
- Retry 소진 상태의 예약 거부
- Retry 예약 시 횟수 증가, 시각 저장, 소유권 제거
- Attempt STARTED만 FAILED 전이
- Version 완료·실패 상태에서 중복 실패 거부
- 실패 유형별 재시도 가능 여부
- 요청 DTO 형식과 오류 메시지 길이 검증
- 단계별 이벤트 선택
- 최초 실패, Retry, 최종 실패와 멱등 재생 Service 분기

### 12.2 Controller 테스트

- ADMIN 정상 요청 200
- 인증 없음·권한 없음 403
- Path ID, Worker ID, Token, Enum, 메시지 Validation 400
- Service 오류의 HTTP·안정적 ErrorCode 매핑
- Entity 직접 노출 없이 응답 DTO 반환

### 12.3 PostgreSQL 통합 테스트

- `next_retry_at` 이전 Job이 Claim되지 않음
- 정확히 예약 시각부터 Claim 가능
- 일반 PENDING과 예약 Retry Job의 Queue 정렬
- Retry 후 새 Token과 새 Attempt 번호 발급
- 최초 Version 영구 실패 시 Document·Version·Job·Attempt 종결
- 새 Version 영구 실패 시 기존 current Version과 Vector 검색 유지
- Retry 소진 시 최종 실패
- Embedding 저장 후 최종 실패 시 대상 Vector STALE
- 동일 실패 요청의 상태·이벤트·Retry 횟수 불변
- 완료와 실패 동시 요청에서 한쪽만 성공
- 이벤트 Insert 또는 Embedding Update 실패 시 전체 Rollback

### 12.4 회귀 테스트

- 최초 인덱싱 완료와 새 Version 검색 전환
- Chunk와 Embedding 생성 멱등 재생
- PENDING Queue 동시 Claim과 `SKIP LOCKED`
- 문서 상태 조회와 Vector 검색 current Version 조건
- 전체 `./gradlew test`

## 13. 커밋 계획

1. `docs: #88 인덱싱 실패 및 지연 재시도 상세 설계 추가`
2. `feat: Embedding Job 지연 재시도 Queue 모델 추가`
3. `feat: 인덱싱 실패 유형과 종료 계약 추가`
4. `feat: 인덱싱 실패 종료 및 재시도 API 구현`
5. `test: 인덱싱 실패 재시도와 동시성 통합 검증`

각 커밋은 관련 테스트를 함께 포함하고 독립적으로 Build 가능한 상태를 유지한다.

## 14. 완료 기준

- 유효한 현재 Attempt만 최초 실패를 보고할 수 있다.
- Retry 가능 오류는 Attempt를 종료하고 Job을 지연 PENDING으로 복귀시킨다.
- 예약 시각 전에는 Claim되지 않고 예약 시각부터 Claim된다.
- 재Claim은 새로운 Claim Token과 Attempt 번호를 사용한다.
- 영구 오류 또는 Retry 소진은 Job과 Version을 최종 FAILED 처리한다.
- 새 Version 실패 시 기존 검색 결과와 current Version이 유지된다.
- 검색 가능한 Version이 없는 최종 실패 문서는 검색에서 제외된다.
- 최종 실패 Version의 ACTIVE Embedding은 STALE이 된다.
- 같은 실패 요청은 Retry 횟수와 이벤트를 중복 생성하지 않는다.
- 완료와 실패의 동시 요청에서 부분 상태가 남지 않는다.
- Transaction 실패 시 모든 상태와 이벤트 변경이 Rollback된다.
- 실제 PostgreSQL 통합 테스트와 전체 테스트가 통과한다.
