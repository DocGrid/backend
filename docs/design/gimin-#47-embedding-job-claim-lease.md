# Issue #47 PENDING Job Claim 및 Lease Lock 상세 설계

closes #47

## 1. 목적

문서 업로드가 완료되면 `embedding_jobs`에 비동기 인덱싱 작업이 `PENDING` 상태로 저장된다. 여러
Worker가 동시에 실행되는 환경에서는 각 Worker가 단순히 PENDING 목록을 조회한 뒤 상태를 변경하는
방식으로 작업을 가져가면 같은 Job을 중복 처리할 수 있다.

```text
Worker A: PENDING Job 10 조회
Worker B: PENDING Job 10 조회
Worker A: Job 10 처리 시작
Worker B: Job 10 처리 시작
```

이 중복은 파싱과 임베딩 모델 호출을 불필요하게 반복할 뿐 아니라 Chunk와 Vector 중복 저장, 상태
전이 충돌, 완료 결과 덮어쓰기까지 일으킬 수 있다. 애플리케이션에서 먼저 조회하고 나중에 갱신하는
두 단계 처리만으로는 동시성을 안전하게 제어할 수 없다.

이 이슈의 목적은 살아 있는 Worker가 처리할 PENDING Job 하나를 DB에서 원자적으로 Claim하고,
제한된 시간 동안 그 Worker가 소유권을 가졌음을 나타내는 Lease 정보를 기록하는 것이다.

시스템은 Claim 직후 다음 질문에 답할 수 있어야 한다.

```text
어떤 Job이 선택됐는가?
어떤 Worker가 Job을 소유하는가?
이번 소유권을 식별하는 Token은 무엇인가?
소유권은 언제 시작됐는가?
언제까지 유효한가?
Job 상태가 PENDING에서 PROCESSING으로 바뀌었는가?
동시에 접근한 다른 Worker가 같은 Job을 가져가지 않았는가?
```

## 2. 범위

### 2.1 포함 범위

- Heartbeat 기준으로 Claim 가능한 Worker 검증
- PENDING Job 한 건 선택
- `priority DESC`, `created_at ASC`, `id ASC` 순서의 Queue 정책
- PostgreSQL `FOR UPDATE SKIP LOCKED`를 사용한 동시 Claim 제어
- Claim 전체 흐름을 하나의 Transaction으로 처리
- Job 상태를 `PENDING`에서 `PROCESSING`으로 전환
- `locked_by_worker_id`, `locked_at`, `lock_expires_at` 기록
- Claim마다 UUID `claim_token` 생성 및 저장
- 최초 처리 시작 시 `started_at` 기록
- `LOCKED` 인덱싱 이벤트 저장
- 관리자용 Claim API 제공
- Claim 대상이 없을 때 `204 No Content` 반환
- Worker 없음과 Claim 불가 상태의 오류 구분
- Lease 기간 설정 및 유효성 검증
- Migration, Repository 잠금, Service, API, 실제 PostgreSQL 동시성 검증

### 2.2 제외 범위

- Worker의 주기적인 Job Polling Loop
- 원본 파일 다운로드
- 문서 파싱
- Chunk 생성 및 저장
- 임베딩 서버 호출
- Vector 저장
- `embedding_job_attempts` 생성 및 실행 이력 관리
- 처리 중 Lease 연장
- 만료된 Lease 회수
- DEAD Worker가 소유한 PROCESSING Job 복구
- Claim Token을 이용한 완료·실패 API
- 재시도 횟수 증가와 재시도 Queue 정책
- Job 취소

이번 구현은 소유권을 획득하는 시점까지만 책임진다. Lease 만료 복구가 아직 포함되지 않으므로 만료된
PROCESSING Job을 다시 PENDING으로 되돌리거나 다른 Worker에게 즉시 재할당하지 않는다.

## 3. 시스템 내 위치

전체 비동기 인덱싱 흐름은 다음 단계로 확장된다.

```text
문서 업로드
  → documents / document_versions 저장
  → embedding_jobs PENDING 생성

Worker 실행
  → worker_nodes 등록
  → Heartbeat 갱신

Job 소유권 획득
  → Worker 생존 상태 검증
  → PENDING Job 행 잠금
  → PROCESSING 전환
  → Lease와 Claim Token 기록
  → LOCKED 이벤트 저장

후속 처리
  → 원본 파일 읽기
  → 파싱
  → 청킹
  → 임베딩
  → INDEXED 또는 FAILED 전환
```

Worker 등록과 Heartbeat는 Claim의 선행 조건이다. 등록되지 않았거나 Heartbeat가 만료된 Worker는
Queue에 PENDING Job이 있어도 Claim할 수 없다.

## 4. 핵심 설계 원칙

### 4.1 DB가 최종 동시성 제어 지점이다

여러 애플리케이션 인스턴스는 같은 JVM Lock을 공유하지 않는다. 따라서 Java의 `synchronized`나
프로세스 내부 Mutex로는 분산 Worker 간 중복 Claim을 방지할 수 없다. 모든 Worker가 공유하는
PostgreSQL의 행 잠금을 소유권 결정 지점으로 사용한다.

### 4.2 선택과 상태 변경은 같은 Transaction에서 수행한다

행을 잠근 Transaction이 종료되기 전에 상태와 Lease를 기록해야 한다.

```text
잘못된 흐름
Transaction 1: PENDING 조회 후 Commit
Transaction 2: PROCESSING 변경
→ 두 Transaction 사이에 다른 Worker가 같은 Job을 조회할 수 있음

적용 흐름
Transaction 시작
→ PENDING 행 SELECT FOR UPDATE SKIP LOCKED
→ PROCESSING 및 Lease 기록
→ LOCKED 이벤트 저장
→ Commit
```

### 4.3 저장 상태와 소유권 정보를 함께 바꾼다

Claim 성공은 단순 조회 성공이 아니다. 다음 필드가 하나의 논리적 변경으로 저장돼야 한다.

```text
status = PROCESSING
locked_by_worker_id = 요청 Worker ID
claim_token = 새 UUID
locked_at = Claim 시각
lock_expires_at = Claim 시각 + leaseDuration
started_at = 최초 Claim 시각
```

중간 저장 실패가 발생하면 Transaction 전체가 Rollback돼 Job은 PENDING 상태로 남는다.

### 4.4 빈 Queue는 오류가 아니다

PENDING Job이 없거나 다른 Worker가 현재 모든 후보 행을 잠근 상황은 정상적인 Polling 결과다. 예외를
발생시키지 않고 빈 결과를 반환하며 HTTP 계층에서는 `204 No Content`로 표현한다.

## 5. 구성요소와 책임

### 5.1 `EmbeddingJobRepository`

Queue 정렬 규칙을 적용해 다음 PENDING Job 한 건을 조회하고 행 잠금을 획득한다.

```sql
SELECT job.*
FROM embedding_jobs job
WHERE job.status = 'PENDING'
ORDER BY job.priority DESC,
         job.created_at ASC,
         job.id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED
```

### 5.2 `EmbeddingJobClaimService`

Claim Use Case의 Transaction 경계를 제공한다.

```text
Worker 조회
→ 실질 상태 검증
→ 다음 PENDING Job 잠금 조회
→ Claim Token 생성
→ Lease 만료 시각 계산
→ Entity 상태 전이
→ LOCKED 이벤트 저장
→ 응답 DTO 변환
```

### 5.3 `EmbeddingJob`

`claim()` 메서드가 PENDING에서 PROCESSING으로의 상태 전이와 소유권 필드 설정을 담당한다.
PENDING이 아닌 Entity에 Claim을 요청하면 `IllegalStateException`을 발생시켜 도메인 불변식을
방어한다.

### 5.4 `EmbeddingJobConverter`

Claim된 Entity를 API 응답으로 변환한다. Controller가 Entity 또는 Lazy 연관관계를 직접 노출하지
않게 한다.

### 5.5 `IndexingEventRepository`

Job 상태 전이와 같은 Transaction에서 `LOCKED` 이벤트를 append-only 방식으로 저장한다.

### 5.6 `IndexingJobAdminController`

Worker ID를 받아 Claim Service를 호출하고 결과 유무에 따라 `200 OK` 또는 `204 No Content`를
반환한다.

### 5.7 `IndexingWorkerProperties`

Heartbeat 설정과 함께 Lease 기간을 관리한다. 기본 Lease는 5분이며 0보다 커야 한다.

## 6. Claim 대상 Worker 검증

### 6.1 Worker 존재 여부

요청한 `workerId`에 대응하는 `worker_nodes` 행이 없으면 Claim을 수행할 주체가 없으므로
`WORKER_NOT_FOUND` 오류를 반환한다.

```text
worker_nodes.findById(workerId)
  ├─ 없음 → 404 WORKER-001
  └─ 있음 → 실질 상태 검증
```

### 6.2 저장 상태와 실질 상태

Worker가 DB에 `ACTIVE`로 저장돼 있어도 Heartbeat가 오래됐다면 실제로 살아 있다고 볼 수 없다.
이번 구현에서는 별도의 DB 상태 갱신 없이 다음 기준으로 실질 상태를 계산한다.

```text
heartbeatDeadline = claimedAt - deadThreshold

저장 상태가 STOPPED 또는 DEAD
→ 해당 상태 유지

ACTIVE 또는 IDLE이지만 last_heartbeat_at이 없거나
last_heartbeat_at <= heartbeatDeadline
→ 실질 DEAD

그 외
→ 저장 상태 유지
```

Claim 가능한 실질 상태는 `ACTIVE`, `IDLE`뿐이다.

| 저장 상태 | Heartbeat | 실질 상태 | Claim |
|---|---|---|---|
| ACTIVE | 유효 | ACTIVE | 가능 |
| IDLE | 유효 | IDLE | 가능 |
| ACTIVE | 만료 또는 없음 | DEAD | 불가 |
| IDLE | 만료 또는 없음 | DEAD | 불가 |
| STOPPED | 무관 | STOPPED | 불가 |
| DEAD | 무관 | DEAD | 불가 |

Claim 불가 상태는 `409 Conflict`, `WORKER-002`로 반환한다. Worker 검증이 실패하면 Job 조회 쿼리는
실행하지 않는다.

## 7. Queue 선택 정책

### 7.1 정렬 기준

Queue는 다음 순서로 Job을 선택한다.

```text
1. priority가 높은 Job
2. 같은 priority에서는 먼저 생성된 Job
3. 생성 시각도 같으면 ID가 작은 Job
```

| 기준 | 방향 | 이유 |
|---|---|---|
| `priority` | DESC | 긴급 작업 우선 |
| `created_at` | ASC | 같은 우선순위에서 FIFO |
| `id` | ASC | 동일 시각에서도 결정적인 순서 보장 |

`status = 'PENDING'` 조건을 SQL에 포함하므로 PROCESSING, INDEXED, FAILED, CANCELED Job은 후보가
아니다.

### 7.2 우선순위 기아 가능성

높은 priority Job이 계속 유입되면 낮은 priority Job이 오래 대기할 수 있다. 현재 요구사항은 명시적인
우선순위 Queue이므로 Aging 정책은 적용하지 않는다. 운영 지표에서 낮은 우선순위의 대기 시간이 문제가
되면 priority 보정 또는 생성 시각 기반 Aging을 별도 설계한다.

## 8. `FOR UPDATE SKIP LOCKED` 동작

### 8.1 `FOR UPDATE`

선택된 Job 행에 배타적 행 잠금을 건다. 같은 행을 변경하거나 일반 `FOR UPDATE`로 가져가려는 다른
Transaction은 해당 잠금이 해제될 때까지 진행할 수 없다.

### 8.2 `SKIP LOCKED`

이미 다른 Transaction이 잠근 후보를 기다리지 않고 건너뛴다.

```text
Job 10: priority 10, Worker A가 잠금 보유
Job 11: priority 5, 잠금 없음

Worker B Claim
→ Job 10에서 대기하지 않음
→ Job 10 건너뜀
→ Job 11 잠금 및 Claim
```

이 정책은 Worker 수가 늘어날 때 각 Worker가 서로 다른 Job을 병렬로 가져가도록 한다. 모든 PENDING
후보가 잠겨 있으면 조회 결과는 비어 있으며 Claim API는 204를 반환한다.

### 8.3 잠금 유지 범위

행 잠금은 Repository 메서드가 반환될 때가 아니라 Claim Service Transaction이 Commit 또는
Rollback될 때 해제된다.

```text
SELECT FOR UPDATE
→ Entity claim
→ IndexingEvent INSERT
→ Transaction Commit
→ 행 잠금 해제
```

따라서 Repository 잠금 조회는 반드시 Transaction 내부에서 호출해야 한다.

## 9. Claim Transaction 상세 흐름

```text
POST /admin/indexing-jobs/claim?workerId=7
  │
  ├─ SecurityFilterChain
  │    └─ ADMIN 권한 확인
  │
  ├─ IndexingJobAdminController
  │    └─ EmbeddingJobClaimService.claim(7)
  │
  ├─ Transaction 시작
  │
  ├─ 현재 시각 claimedAt 계산
  │
  ├─ worker_nodes id=7 조회
  │    ├─ 없음 → WORKER_NOT_FOUND, Rollback
  │    └─ 있음
  │
  ├─ Heartbeat 기준 실질 상태 계산
  │    ├─ ACTIVE/IDLE → 계속
  │    └─ DEAD/STOPPED → WORKER_NOT_AVAILABLE, Rollback
  │
  ├─ 다음 PENDING Job SELECT FOR UPDATE SKIP LOCKED
  │    ├─ 없음 → Optional.empty, Commit, 204
  │    └─ Job 존재 및 행 잠금 획득
  │
  ├─ claimToken = UUID.randomUUID()
  ├─ lockExpiresAt = claimedAt + leaseDuration
  │
  ├─ EmbeddingJob.claim(...)
  │    ├─ status = PROCESSING
  │    ├─ lockedByWorker = Worker 7
  │    ├─ claimToken 저장
  │    ├─ lockedAt 저장
  │    ├─ lockExpiresAt 저장
  │    └─ startedAt이 null이면 claimedAt 저장
  │
  ├─ LOCKED IndexingEvent INSERT
  │
  ├─ ClaimedEmbeddingJobResponse 생성
  │
  ├─ Transaction Commit
  │    ├─ embedding_jobs UPDATE
  │    ├─ indexing_events INSERT 확정
  │    └─ 행 잠금 해제
  │
  └─ 200 OK
```

## 10. Lease 모델

### 10.1 Lease가 필요한 이유

영구 Lock만 기록하면 Worker 프로세스가 비정상 종료됐을 때 Job을 다시 처리할 수 없다. Lease는
소유권에 만료 시각을 두어 후속 복구 작업이 안전하게 소유권을 회수할 기준을 제공한다.

```text
locked_at
→ 현재 소유권이 시작된 시각

lock_expires_at
→ 현재 소유권의 유효 기한
```

현재 구현은 Lease를 발급만 한다. 만료 판정과 회수는 후속 기능의 책임이다.

### 10.2 설정

```yaml
indexing:
  worker:
    lease-duration: ${INDEXING_WORKER_LEASE_DURATION:5m}
```

환경별 설정 예시는 다음과 같다.

```text
로컬/테스트
INDEXING_WORKER_LEASE_DURATION=30s

운영
INDEXING_WORKER_LEASE_DURATION=5m
```

`lease-duration`은 반드시 0보다 커야 한다. 0 또는 음수면 발급 직후 만료되는 잘못된 소유권이
생기므로 애플리케이션 설정 검증 단계에서 시작을 차단한다.

### 10.3 Lease 길이 선택 기준

Lease는 일반적인 Job 처리 시간보다 충분히 길어야 하며, 장애 후 복구 목표 시간보다 지나치게 길면
안 된다.

```text
너무 짧음
→ 정상 처리 중 Lease 만료 가능성 증가

너무 김
→ Worker 장애 후 Job 복구 지연
```

실제 파싱·임베딩 처리 시간이 도입되면 Lease 연장과 함께 p95 또는 p99 처리 시간을 기준으로 운영값을
조정한다.

## 11. Claim Token

### 11.1 역할

Worker ID만으로는 같은 Worker 프로세스가 과거에 가졌던 소유권과 현재 가진 소유권을 구분하기 어렵다.
Claim할 때마다 새 UUID Token을 생성하면 후속 완료·실패 요청이 현재 Lease 소유자에게서 왔는지 확인할
수 있다.

```text
첫 번째 Claim
worker_id = 7
claim_token = TOKEN-A

Lease 만료 후 재Claim
worker_id = 9
claim_token = TOKEN-B

늦게 도착한 첫 번째 Worker의 완료 요청
token = TOKEN-A
→ 현재 token TOKEN-B와 다름
→ 거부 가능
```

### 11.2 저장 형식

`embedding_jobs.claim_token`을 `VARCHAR(36)` nullable 컬럼으로 추가한다.

```sql
ALTER TABLE embedding_jobs
    ADD COLUMN claim_token VARCHAR(36);
```

기존 PENDING, 완료, 실패 Job에는 Claim Token이 없으므로 nullable로 유지한다. 신규 Claim이 성공하면
UUID 문자열을 기록한다.

### 11.3 노출 정책

Token은 Claim 성공 응답에 포함해 후속 처리에서 소유권 증명에 사용할 수 있게 한다. 로그와
`indexing_events.message`에는 Token을 기록하지 않는다. 향후 완료·실패 API에서는 Worker ID와 Token을
모두 비교해야 한다.

## 12. Job 상태 전이와 불변식

### 12.1 허용 전이

이번 기능이 수행하는 상태 전이는 하나다.

```text
PENDING → PROCESSING
```

### 12.2 Claim 후 필드 불변식

PROCESSING으로 전환된 Claim Job은 다음 조건을 만족해야 한다.

```text
locked_by_worker_id IS NOT NULL
claim_token IS NOT NULL
locked_at IS NOT NULL
lock_expires_at IS NOT NULL
lock_expires_at > locked_at
started_at IS NOT NULL
```

### 12.3 `started_at` 정책

`started_at`은 Job이 최초로 처리되기 시작한 시각이다. 현재는 PENDING Job의 최초 Claim이므로
`claimedAt`을 기록한다. 후속 재Claim에서는 기존 `started_at`을 보존해 전체 처리 시작 시각과 이번
Lease 시작 시각을 구분한다.

## 13. 이벤트 기록

Claim 성공 시 다음 `indexing_events` 행을 저장한다.

| 필드 | 값 |
|---|---|
| `embedding_job_id` | Claim된 Job ID |
| `event_type` | `LOCKED` |
| `from_status` | `PENDING` |
| `to_status` | `PROCESSING` |
| `message` | Worker가 Embedding Job을 Claim했다는 설명 |
| `occurred_at` | Claim 시각 |

Job 변경과 이벤트 저장은 같은 Transaction에 포함된다.

```text
Job UPDATE 성공 + Event INSERT 실패
→ 전체 Rollback

Job UPDATE 실패
→ Event도 저장되지 않음
```

따라서 상태는 PROCESSING인데 LOCKED 이벤트가 없는 부분 성공을 방지한다.

## 14. 관리자 API

### 14.1 Endpoint

```http
POST /admin/indexing-jobs/claim?workerId={workerId}
```

현재 프로젝트의 보안 정책에서 `/admin/**`는 ADMIN 권한으로 보호된다. 인증되지 않았거나 ADMIN 권한이
없는 요청은 기존 정책에 따라 403으로 거부된다. Worker 전용 인증 체계가 도입되기 전까지 내부 운영용
Endpoint로 사용한다.

### 14.2 성공 응답

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "jobId": 10,
    "status": "PROCESSING",
    "workerId": 7,
    "documentVersionId": 5,
    "embeddingModelId": 2,
    "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab",
    "lockedAt": "2026-07-22T15:00:00",
    "lockExpiresAt": "2026-07-22T15:05:00"
  },
  "timestamp": "2026-07-22 15:00:00"
}
```

응답에 문서 버전 ID와 임베딩 모델 ID를 포함해 Worker가 후속 처리 대상을 식별할 수 있게 한다.

### 14.3 빈 Queue 응답

```http
HTTP/1.1 204 No Content
```

응답 Body는 없다. 다음 경우를 모두 정상적인 빈 결과로 취급한다.

- PENDING Job이 없음
- 현재 조회 시점에 모든 PENDING 후보가 다른 Transaction에 의해 잠겨 있음

### 14.4 오류 응답

| 상황 | HTTP | 오류 코드 | 처리 |
|---|---:|---|---|
| 미인증 또는 ADMIN 아님 | 403 | 기존 Security 응답 | Controller 진입 전 차단 |
| Worker ID 없음 | 404 | `WORKER-001` | Job 조회 안 함 |
| Worker STOPPED/DEAD | 409 | `WORKER-002` | Job 조회 안 함 |
| Worker Heartbeat 만료 | 409 | `WORKER-002` | Job 조회 안 함 |

## 15. 동시 실행 시나리오

### 15.1 하나의 Job, 두 Worker

```text
초기 상태
Job 10 = PENDING

Worker A Transaction
→ Job 10 SELECT FOR UPDATE 성공
→ Job 10 행 잠금

Worker B Transaction
→ Job 10은 잠겨 있으므로 SKIP
→ 다른 PENDING Job 없음
→ 빈 결과

Worker A
→ PROCESSING + Lease 저장
→ Commit

최종 상태
Job 10 소유자 = Worker A
LOCKED 이벤트 = 1건
Worker B 결과 = 없음
```

### 15.2 두 개의 Job, 두 Worker

```text
Worker A → 우선순위가 가장 높은 Job 10 잠금
Worker B → 잠긴 Job 10을 건너뛰고 Job 11 잠금
Worker A → Job 10 Claim
Worker B → Job 11 Claim
```

Queue 조회를 직렬화하지 않고 행 단위로 분산하므로 Worker 확장 시 병렬 처리량을 유지한다.

### 15.3 Claim 도중 Event 저장 실패

```text
Job 행 잠금 성공
→ Job PROCESSING 변경
→ Event INSERT 실패
→ Transaction Rollback
→ Job은 다시 PENDING으로 보임
→ 다른 Worker가 후속 Claim 가능
```

### 15.4 Worker 검증 후 Heartbeat 만료

Claim Transaction은 요청 시점의 Worker 상태와 Heartbeat를 기준으로 판단한다. 검증 직후 Worker가
비정상 종료될 수 있으므로 Lease 만료 복구가 최종 장애 안전장치가 된다. Claim 단계에서 프로세스의
미래 생존까지 보장할 수는 없다.

## 16. 실패 및 경계 조건

### 16.1 잘못된 Worker ID

Job 행 잠금 전에 404를 반환한다. Queue 상태에는 영향이 없다.

### 16.2 만료된 Heartbeat

DB 저장 상태가 ACTIVE여도 실질 DEAD로 계산해 409를 반환한다. 조회 과정에서 Worker의 저장 상태를
DEAD로 변경하지 않는다.

### 16.3 PENDING이 아닌 Entity Claim

Repository SQL이 PENDING만 선택하지만 Entity도 상태를 다시 검사한다. 다른 호출 경로가 잘못된 상태의
Job에 `claim()`을 호출해도 도메인 계층에서 차단된다.

### 16.4 Transaction Rollback

예외가 발생하면 다음 변경이 모두 취소된다.

- PROCESSING 상태 전환
- Worker 연결
- Claim Token
- Lease 시각
- startedAt
- LOCKED 이벤트

### 16.5 API 재호출

같은 Worker가 API를 다시 호출하면 기존 PROCESSING Job을 다시 반환하지 않고 다음 PENDING Job을
Claim한다. 현재 소유 Job 조회나 Lease 연장은 별도 기능이다.

## 17. 데이터베이스 변경

### 17.1 Migration

`V33__add_embedding_job_claim_token.sql`에서 `claim_token VARCHAR(36)`을 추가한다.

기존 행을 보정할 필요가 없고 nullable 컬럼이므로 테이블에 기존 데이터가 있어도 적용할 수 있다.

### 17.2 기존 Index 활용

Queue 조회는 기존 복합 Index를 활용한다.

```text
idx_embedding_jobs_status_priority_created_at
(status, priority, created_at)
```

정렬의 마지막 기준인 `id`는 결정적인 순서를 위한 Tie-breaker다. 운영 데이터에서 실행 계획과 대기 시간이
문제가 되면 `(status, priority DESC, created_at, id)` 형태의 Index 조정을 측정 후 검토한다.

## 18. 코드 구조

```text
domain/embedding/
├─ controller/
│  └─ IndexingJobAdminController
├─ converter/
│  └─ EmbeddingJobConverter
├─ dto/response/
│  └─ ClaimedEmbeddingJobResponse
├─ entity/
│  └─ EmbeddingJob
├─ repository/
│  └─ EmbeddingJobRepository
└─ service/command/
   └─ EmbeddingJobClaimService

domain/worker/
├─ config/
│  └─ IndexingWorkerProperties
└─ repository/
   └─ IndexingEventRepository

global/exception/
└─ ErrorCode

resources/
├─ application.yml
└─ db/migration/
   └─ V33__add_embedding_job_claim_token.sql
```

## 19. 테스트 전략

### 19.1 Entity 테스트

- PENDING Job Claim 시 PROCESSING 전환
- Worker, Token, lockedAt, lockExpiresAt 설정
- 최초 startedAt 기록
- PENDING이 아닌 Job Claim 차단

### 19.2 설정 테스트

- 양수 Lease 기간 허용
- 0인 Lease 기간 거부
- 음수 Lease 기간 거부

### 19.3 Service 테스트

- 유효한 ACTIVE Worker가 PENDING Job Claim
- 유효한 IDLE Worker가 PENDING Job Claim
- Worker가 없으면 404 오류
- STOPPED 또는 DEAD Worker면 409 오류
- Heartbeat가 만료된 ACTIVE Worker면 409 오류
- Queue가 비어 있으면 빈 결과
- Claim 성공 시 LOCKED 이벤트 저장
- Claim Token이 UUID 형식이고 Lease 만료 시각이 정확한지 검증

### 19.4 Controller 테스트

- ADMIN Claim 성공 시 200
- Claim 대상 없음 시 204
- 일반 사용자 403
- 미인증 사용자 403
- Worker 없음 404
- Worker Claim 불가 409

### 19.5 실제 PostgreSQL 통합 테스트

- Flyway가 `claim_token VARCHAR(36)`을 생성하는지 검증
- priority 내림차순 정렬 검증
- 같은 priority에서 createdAt 오름차순 검증
- PROCESSING Job 제외 검증
- 한 Transaction이 최우선 Job을 잠근 상태에서 다른 Transaction이 다음 Job을 즉시 선택하는지 검증
- 두 Worker가 하나의 Job을 동시에 Claim해도 성공 결과가 정확히 한 건인지 검증
- 최종 DB 상태가 PROCESSING이고 Worker, Token, Lease가 한 소유자 기준으로 저장되는지 검증
- LOCKED 이벤트가 정확히 한 건인지 검증

동시성 테스트는 서로 다른 Thread와 `REQUIRES_NEW` Transaction을 사용한다. 단일 Transaction이나 같은
Persistence Context에서 순차 호출하는 테스트만으로는 실제 행 잠금 경쟁을 검증할 수 없다.

## 20. 운영 관찰 지점

Claim 도입 후 다음 지표를 확인할 수 있어야 한다.

- PENDING Job 수와 가장 오래 대기한 시간
- Worker별 Claim 성공 수
- Claim API의 204 비율
- Worker별 PROCESSING Job 수
- Lease 만료 예정 또는 만료 Job 수
- PENDING에서 PROCESSING으로 전환되는 시간
- 낮은 priority Job의 최대 대기 시간

현재 구현은 별도 Metric을 추가하지 않지만 DB와 Event를 통해 확인할 기반을 남긴다.

## 21. 완료 기준

- Heartbeat가 유효한 ACTIVE 또는 IDLE Worker만 Claim할 수 있다.
- Worker가 없으면 404, Claim 불가 상태면 409로 구분된다.
- PENDING Job이 Queue 정책에 맞춰 한 건 선택된다.
- 잠긴 Job은 대기하지 않고 건너뛴다.
- 동시 요청에서도 하나의 Job은 한 Worker만 Claim한다.
- Claim된 Job은 PROCESSING 상태가 된다.
- Worker ID, Claim Token, lockedAt, lockExpiresAt, startedAt이 기록된다.
- Claim Token은 UUID이며 로그 이벤트에 노출되지 않는다.
- Job 변경과 LOCKED 이벤트가 하나의 Transaction으로 저장된다.
- Queue가 비어 있으면 204를 반환한다.
- ADMIN 권한이 없는 요청은 403으로 차단된다.
- 실제 PostgreSQL에서 Migration과 `FOR UPDATE SKIP LOCKED` 동작을 검증한다.
- 후속 Job 실행과 Lease 만료 복구에서 사용할 소유권 정보가 준비된다.

## 22. 후속 확장

이 기반 위에서 다음 기능을 구현할 수 있다.

```text
Claim 성공
→ embedding_job_attempts 시작 기록
→ 파싱·청킹·임베딩 실행
→ 처리 중 Lease 연장
→ Worker ID + Claim Token으로 완료/실패 검증
→ INDEXED 또는 FAILED 전환

Lease 만료
→ 만료 PROCESSING Job 탐색
→ 이전 Token 무효화
→ 재시도 가능 여부 판단
→ PENDING 복구 또는 최종 FAILED
→ RETRY/FAILED 이벤트 기록
```

특히 완료·실패 처리에서는 Job ID만 신뢰하면 안 된다. 현재 저장된 `locked_by_worker_id`와
`claim_token`이 요청 값과 모두 일치하고 Lease가 유효한지 확인해야 오래된 Worker의 늦은 결과가 최신
처리 결과를 덮어쓰는 것을 방지할 수 있다.
