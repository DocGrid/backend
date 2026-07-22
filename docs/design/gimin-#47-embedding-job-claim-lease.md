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

## 23. Job Claim은 Job 실행과 다르다

Claim과 실제 문서 처리를 하나의 개념으로 보면 Transaction 범위와 장애 복구 기준이 불명확해진다.

```text
Job Claim
→ 누가 이 Job을 처리할 권한을 가졌는지 결정

Job 실행
→ 소유권을 가진 Worker가 파싱·청킹·임베딩 수행
```

Claim은 매우 짧은 DB 작업이어야 한다.

```text
Worker 검증
→ Queue 행 하나 잠금
→ 소유권 기록
→ Commit
```

반대로 Job 실행은 수초에서 수분이 걸릴 수 있다.

```text
원본 파일 다운로드
→ 텍스트 파싱
→ Chunk 분리
→ Embedding Server 호출
→ Vector 저장
```

파싱과 임베딩을 Claim Transaction 안에서 실행하면 행 잠금이 처리 시간 전체에 걸쳐 유지된다.

```text
Transaction 시작
→ Job 행 잠금
→ 2분 동안 임베딩 호출
→ Commit
```

이 구조에서는 다음 문제가 발생한다.

- DB Connection을 긴 시간 점유한다.
- 장애 시 Rollback 범위가 너무 커진다.
- 같은 Job을 확인하는 관리 쿼리와 복구 쿼리가 오래 대기할 수 있다.
- 외부 Embedding Server 지연이 DB Transaction 지연으로 전파된다.

따라서 Claim Transaction은 소유권 기록 직후 끝낸다.

```text
짧은 Claim Transaction
→ PROCESSING + Lease + Token Commit
→ DB 행 잠금 해제

Transaction 밖의 장기 실행
→ 파싱·청킹·임베딩
```

현재 구현은 첫 번째 단계인 소유권 획득까지만 제공한다.

## 24. Worker, Thread, Polling, Job의 관계

Worker ID가 하나라고 해서 반드시 한 번에 Job 하나만 처리해야 하는 것은 아니다.

```text
Spring Boot 프로세스
└─ Worker 실행 인스턴스 7
   ├─ Polling Thread
   └─ 실행 Thread Pool
      ├─ Thread 1 → Job 100
      ├─ Thread 2 → Job 101
      └─ Thread 3 → Job 102
```

각 개념의 역할은 다음과 같다.

```text
Worker
→ 프로세스를 대표하는 장기 실행 주체

Thread
→ Java 코드를 실제로 실행하는 실행 단위

Polling
→ 처리할 Job이 있는지 주기적으로 Claim API를 호출하는 행위

Job
→ 문서 버전 하나를 인덱싱하는 단기 작업
```

현재 Claim Service는 Worker 하나가 이미 소유한 Job 개수를 제한하지 않는다.

```text
Job 100.locked_by_worker_id = 7
Job 101.locked_by_worker_id = 7
Job 102.locked_by_worker_id = 7
```

여러 실행 Thread를 가진 Worker라면 정상적인 상태다. 다만 각 Claim Token은 달라야 한다.

```text
Job 100.claim_token = TOKEN-A
Job 101.claim_token = TOKEN-B
Job 102.claim_token = TOKEN-C
```

프로세스별 최대 동시 처리 수는 Claim Service가 아니라 후속 Polling과 Executor 설정이 결정한다.

```text
처리 가능한 Thread가 없음
→ 새 Claim 요청을 보내지 않음

Thread 하나가 비어 있음
→ Job 하나 Claim
→ 해당 Thread에 전달
```

이 용량 제어가 없으면 하나의 Worker가 실행 능력보다 많은 Job을 Claim해 Lease만 보유할 수 있다. 따라서
후속 Poller는 반드시 사용 가능한 실행 슬롯 수만큼만 Claim해야 한다.

## 25. DB 행 잠금, Lease, Claim Token은 서로 다른 장치다

세 개념은 모두 소유권과 관련 있지만 목적과 수명이 다르다.

| 장치 | 존재 위치 | 수명 | 해결하는 문제 |
|---|---|---|---|
| DB 행 잠금 | PostgreSQL Transaction | Claim Transaction이 끝날 때까지 | 같은 순간의 중복 선택 |
| Lease | `lock_expires_at` 컬럼 | 설정된 기간 | Commit 이후 Worker 장애 복구 기준 |
| Claim Token | `claim_token` 컬럼 | 다음 Claim으로 교체될 때까지 | 오래된 Worker 결과 식별 |

### 25.1 DB 행 잠금의 수명

```text
SELECT ... FOR UPDATE
→ 행 잠금 시작
→ PROCESSING UPDATE
→ LOCKED 이벤트 INSERT
→ Commit
→ 행 잠금 종료
```

행 잠금은 Worker가 파싱과 임베딩을 마칠 때까지 유지되지 않는다. Claim을 원자적으로 만드는 짧은
동시성 장치다.

### 25.2 Lease의 수명

```text
locked_at = 15:00:00
lock_expires_at = 15:05:00
```

Transaction이 15:00:00.050에 끝나 행 잠금이 해제돼도 Lease는 DB에 남는다. 다른 Worker는 PENDING만
조회하므로 PROCESSING Job을 Claim하지 않는다. 후속 Reaper가 15:05:00 이후에 만료를 판단할 때
사용한다.

### 25.3 Claim Token의 수명

```text
첫 번째 Claim
Worker 7 / TOKEN-A

Lease 만료 후 두 번째 Claim
Worker 9 / TOKEN-B
```

첫 Worker가 뒤늦게 완료 요청을 보내면 Job ID만으로는 이전 소유자인지 알 수 없다.

```text
완료 요청 Job ID = 100
현재 Job ID = 100
→ ID만 보면 같은 Job

완료 요청 Token = TOKEN-A
현재 Token = TOKEN-B
→ 현재 소유자가 아님
```

따라서 후속 완료·실패 처리에서는 다음 조건을 모두 확인해야 한다.

```text
job.id = 요청 Job ID
locked_by_worker_id = 요청 Worker ID
claim_token = 요청 Token
lock_expires_at > 현재 시각
status = PROCESSING
```

DB 행 잠금만으로는 Commit 이후의 장애를 복구할 수 없고, Lease만으로는 과거 요청을 식별할 수 없다.
세 장치를 분리한 이유다.

## 26. Spring `@Transactional` 내부 동작

`EmbeddingJobClaimService`의 `@Transactional`은 단순히 메서드에 표시를 남기는 Annotation이 아니다.
Spring AOP Proxy가 메서드 호출 전후에 실제 Transaction 경계를 만든다.

```text
Controller
→ Spring Proxy가 감싼 EmbeddingJobClaimService 호출
→ TransactionInterceptor 실행
→ DB Transaction 시작
→ 실제 claim() 메서드 실행
→ 정상 반환이면 Commit
→ Commit 성공 후 Controller로 결과 반환
```

실행 단계를 더 자세히 보면 다음과 같다.

```text
1. HTTP 요청이 Security Filter를 통과한다.
2. Controller가 embeddingJobClaimService.claim(workerId)를 호출한다.
3. Spring Transaction Proxy가 Connection을 확보하고 Transaction을 시작한다.
4. WorkerNodeRepository가 Worker를 조회한다.
5. EmbeddingJobRepository가 Native SQL로 PENDING 행 잠금을 시도한다.
6. 조회 결과가 JPA Persistence Context의 Managed Entity가 된다.
7. EmbeddingJob.claim()이 Managed Entity의 필드를 변경한다.
8. IndexingEventRepository.save()가 신규 이벤트를 Persistence Context에 등록한다.
9. Converter가 Transaction 안에서 LAZY 연관 Entity의 ID를 읽는다.
10. Service 메서드가 응답 DTO를 반환한다.
11. Transaction Proxy가 Flush와 Commit을 수행한다.
12. PostgreSQL이 행 잠금을 해제한다.
13. Commit에 성공한 DTO가 Controller로 반환된다.
14. Controller가 200 응답을 생성한다.
```

### 26.1 Job에 명시적 `save()`가 없는 이유

Repository에서 조회한 `EmbeddingJob`은 Managed Entity다.

```java
EmbeddingJob job = repository.findNextPendingForUpdate();
job.claim(...);
```

Transaction Commit 직전 Hibernate는 최초 Snapshot과 현재 필드를 비교한다.

```text
최초 status = PENDING
현재 status = PROCESSING
→ 변경 감지
→ embedding_jobs UPDATE 생성
```

이를 Dirty Checking이라 한다. 기존 Entity를 변경한 뒤 `save()`를 다시 호출할 필요가 없다.

### 26.2 Event에는 `save()`가 필요한 이유

`IndexingEvent`는 기존 행을 수정하는 Entity가 아니라 새로 생성하는 Entity다.

```text
EmbeddingJob
→ Repository 조회로 이미 Managed 상태

IndexingEvent
→ Builder로 새 객체 생성
→ Repository.save()로 Persistence Context 등록 필요
```

### 26.3 Commit에서 오류가 발생하면 어떻게 되는가

Service 메서드 본문이 DTO를 만들었더라도 Commit이 실패하면 Controller는 성공 DTO를 받지 못한다.

```text
Service 본문 정상 종료
→ Flush 중 Event INSERT 실패
→ Transaction Rollback
→ Proxy가 예외 전달
→ 200 응답 생성 안 됨
```

따라서 응답이 반환됐다는 것은 Service 코드 실행뿐 아니라 Transaction Commit까지 성공했다는 의미다.

## 27. PostgreSQL에서 후보 행이 선택되는 원리

Queue SQL은 한 줄씩 독립적으로 동작하는 것이 아니라 하나의 SELECT 문으로 후보 필터링, 정렬, 제한,
잠금을 수행한다.

```sql
SELECT job.*
FROM embedding_jobs job
WHERE job.status = 'PENDING'
ORDER BY job.priority DESC,
         job.created_at ASC,
         job.id ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

논리적인 판단 순서는 다음과 같다.

```text
1. status가 PENDING인 행만 후보로 제한한다.
2. priority가 높은 순서로 정렬한다.
3. 같은 priority에서는 created_at이 오래된 행을 앞에 둔다.
4. 생성 시각도 같으면 id가 작은 행을 앞에 둔다.
5. 이미 다른 Transaction이 잠근 행은 건너뛴다.
6. 잠글 수 있는 첫 행 한 건만 반환한다.
```

`id ASC`가 없으면 같은 priority와 created_at을 가진 행의 순서는 DB 실행 계획에 따라 달라질 수 있다.
Tie-breaker를 둬 동일한 데이터에서 선택 순서를 결정적으로 만든다.

### 27.1 PostgreSQL 기본 격리 수준에서의 동작

일반적인 PostgreSQL Transaction 기본 격리 수준은 READ COMMITTED다. 각 SQL 문은 시작 시점에 Commit된
행을 기준으로 Snapshot을 만든다.

두 Worker가 거의 동시에 조회하면 다음 두 실행 순서가 가능하다.

```text
경우 A: Worker B가 Worker A의 Commit 전에 조회

Worker A
→ Job 100 PENDING 확인
→ Job 100 행 잠금

Worker B
→ Job 100도 PENDING Snapshot으로 보일 수 있음
→ 하지만 행 잠금이 있으므로 SKIP
→ 다음 Job 또는 빈 결과
```

```text
경우 B: Worker B가 Worker A의 Commit 후 조회

Worker A
→ Job 100 PROCESSING Commit

Worker B
→ 새 Statement Snapshot에서 Job 100은 PROCESSING
→ WHERE status = 'PENDING' 조건에서 제외
→ 다음 Job 또는 빈 결과
```

두 경우 모두 Worker B는 Job 100을 Claim하지 못한다.

### 27.2 `SKIP LOCKED`가 정확한 Queue 전체 Snapshot을 의미하지 않는 이유

`SKIP LOCKED` 결과는 조회 순간 다른 Transaction의 잠금 상태에 따라 달라진다. 따라서 일반 사용자
목록이나 통계 조회에는 적합하지 않다.

```text
실제 PENDING = Job 100, Job 101
Job 100은 다른 Worker가 잠금 중
현재 Claim 조회 결과 = Job 101
```

이 결과는 Queue 소비자에게는 바람직하지만, “현재 PENDING 전체 목록”을 보여주는 조회에는 불완전하다.
따라서 `SKIP LOCKED`는 Claim 전용 Repository 메서드에서만 사용한다.

## 28. `SKIP LOCKED`를 사용한 이유

행 잠금만 사용하고 `SKIP LOCKED`를 생략할 수도 있다.

```sql
SELECT ...
LIMIT 1
FOR UPDATE;
```

이 경우 모든 Worker가 같은 최우선 Job을 먼저 바라본다.

```text
Worker A → Job 100 잠금
Worker B → Job 100 잠금 해제 대기
Worker C → Job 100 잠금 해제 대기
```

Worker A가 Commit하면 Job 100은 PROCESSING이 되므로 B와 C는 다시 조건을 확인하거나 다음 행을 찾아야
한다. Queue 앞 행 하나가 전체 Worker 진행을 막는 Head-of-line Blocking이 생긴다.

`SKIP LOCKED`를 적용하면 다음과 같이 분산된다.

```text
Worker A → Job 100
Worker B → Job 101
Worker C → Job 102
```

Worker 수가 증가해도 서로 다른 행을 병렬 Claim할 수 있다.

다만 공정성을 완전히 보장하지는 않는다. 특정 행이 반복해서 잠겨 있으면 다른 행이 먼저 처리될 수 있다.
Queue 처리량과 엄격한 전역 순서 중 현재 시스템은 처리량을 선택한다.

## 29. 다른 동시성 방식과 비교

### 29.1 단순 SELECT 후 UPDATE

```text
SELECT PENDING Job
→ 애플리케이션에서 선택
→ UPDATE PROCESSING
```

두 SQL 사이에 경쟁 구간이 생긴다.

```text
Worker A SELECT Job 100
Worker B SELECT Job 100
Worker A UPDATE
Worker B UPDATE
```

조건부 UPDATE로 한 Worker만 성공하게 만들 수 있지만 실패한 Worker가 다시 후보를 찾는 반복 로직과
응답 데이터 구성이 추가로 필요하다.

### 29.2 낙관적 잠금 `@Version`

두 Worker가 같은 Job을 읽은 뒤 Commit 경쟁에서 한쪽에 Optimistic Lock 예외를 발생시킬 수 있다.

```text
장점
→ 일반 조회에서는 잠금을 오래 잡지 않음

단점
→ 충돌이 정상적인 Queue 소비에서 예외로 표현됨
→ 실패 Worker가 다음 Job을 찾기 위해 재시도해야 함
→ Worker 수가 많고 같은 Queue Head를 보면 충돌이 집중됨
```

수정 충돌이 드문 일반 업무 Entity에는 적합하지만, 여러 소비자가 항상 같은 첫 후보를 경쟁하는 Queue에는
`SKIP LOCKED`가 더 직접적이다.

### 29.3 PostgreSQL Advisory Lock

Job ID를 Advisory Lock Key로 변환해 잠글 수 있다. 하지만 후보 행 조회와 Lock 획득이 분리되고, Key
매핑과 Session/Transaction Lock 해제 정책을 애플리케이션이 직접 관리해야 한다.

현재는 보호할 대상이 명확한 DB 행이 있으므로 행 잠금이 더 단순하다.

### 29.4 JVM 내부 Lock 또는 메모리 Queue

```text
Pod A 메모리 Lock
Pod B 메모리 Lock
```

서로 다른 프로세스는 Lock을 공유하지 않는다. 재시작하면 Queue 상태도 사라진다. 현재 시스템은 다중
Worker와 장애 복구를 전제로 하므로 공유 PostgreSQL을 최종 조정 지점으로 사용한다.

### 29.5 외부 Message Broker

Kafka, RabbitMQ, SQS 같은 Broker는 더 높은 처리량과 전달 보장을 제공할 수 있다. 하지만 현재 시스템은
Job 상태, 문서 버전, Worker, 재시도 이력을 PostgreSQL에서 함께 관리하고 있다.

초기 단계에서 Broker를 도입하면 다음 복잡성이 추가된다.

- DB Job 생성과 Message 발행의 이중 쓰기
- Outbox 또는 Transactional Messaging
- Broker 재전달과 DB 상태의 멱등성 처리
- 별도 운영 인프라와 장애 지점

현재 규모에서는 PostgreSQL Queue가 더 단순하다. 처리량과 지연 요구가 DB Queue 한계를 넘는다는 측정이
생기면 Outbox 기반 Broker 전환을 검토한다.

## 30. Worker 검증을 Job 잠금보다 먼저 하는 이유

현재 흐름은 Worker를 먼저 검증하고 Job을 나중에 잠근다.

```text
Worker 조회
→ Effective Status 검증
→ PENDING Job 잠금
```

순서를 반대로 하면 잘못된 요청도 Queue 행을 잠글 수 있다.

```text
PENDING Job 잠금
→ Worker 조회
→ Worker가 DEAD임을 확인
→ Rollback
```

결국 Rollback되더라도 검증 시간 동안 다른 정상 Worker가 해당 행을 건너뛰게 된다. 따라서 값싼 선행
조건을 먼저 확인해 불필요한 Queue 잠금을 줄인다.

### 30.1 검증 직후 Worker가 죽을 수 있는 문제

Heartbeat가 유효하다고 확인한 직후 프로세스가 종료될 수 있다.

```text
15:00:00 Worker ACTIVE 확인
15:00:00.010 Worker 프로세스 Crash
15:00:00.020 Job Claim Commit
```

분산 시스템에서는 검증 시점과 이후 생존을 원자적으로 묶을 수 없다. Worker 프로세스의 미래 생존을 DB
Transaction이 보장할 수도 없다.

이 간격은 Lease로 처리한다.

```text
Worker가 정상 실행
→ 처리 및 Lease 연장

Worker가 Claim 직후 Crash
→ Lease 연장 없음
→ lock_expires_at 경과
→ 후속 Reaper가 Job 회수
```

Heartbeat 검증은 이미 죽은 Worker의 Claim을 줄이고, Lease는 검증 이후 발생한 장애를 복구한다.

## 31. 시간 계산에 `Clock`을 주입한 이유

서비스에서 `LocalDateTime.now()`를 직접 여러 번 호출하면 하나의 Claim 안에서도 기준 시각이 미세하게
달라질 수 있다.

```text
Worker 검증 시각 = 15:00:00.001
locked_at = 15:00:00.006
event occurred_at = 15:00:00.011
```

각 시각이 기술적으로 틀리지는 않지만 같은 사건의 경계 검증이 복잡해진다. 현재 구현은 주입된 Clock으로
한 번만 계산한다.

```java
LocalDateTime claimedAt = LocalDateTime.now(clock);
```

이 하나의 값을 다음에 재사용한다.

```text
Heartbeat DEAD 기준 계산
locked_at
lock_expires_at 계산 기준
started_at
LOCKED 이벤트 occurred_at
```

따라서 다음 불변식이 명확하다.

```text
locked_at = started_at       // 최초 Claim
event.occurred_at = locked_at
lock_expires_at = locked_at + leaseDuration
```

테스트에서는 고정 Clock을 주입해 경계값을 정확히 검증한다.

```text
현재 시각 = 15:00:00
deadThreshold = 30초
deadline = 14:59:30

lastHeartbeatAt = 14:59:30
→ isAfter(deadline) = false
→ DEAD
```

“기준 시각과 정확히 같으면 만료” 정책을 실행 속도에 관계없이 반복 검증할 수 있다.

## 32. 장애 발생 시점별 결과

Claim 흐름의 어느 시점에서 장애가 발생하는지에 따라 DB 결과가 달라진다.

| 장애 시점 | DB 결과 | 복구 방식 |
|---|---|---|
| Worker 검증 전 | 변경 없음 | 다음 요청 가능 |
| 행 잠금 전 | 변경 없음 | 다음 Worker가 Claim |
| 행 잠금 후, 상태 변경 전 | Transaction Rollback | 잠금 해제 후 재Claim |
| 상태 변경 후, Commit 전 | Transaction Rollback | Job은 PENDING 유지 |
| Event 저장 실패 | Transaction Rollback | Job과 Event 모두 미반영 |
| Commit 후, HTTP 응답 전 | Job은 PROCESSING | Lease 만료 복구 필요 |
| 응답 후 처리 중 Worker Crash | Job은 PROCESSING | Lease 만료 복구 필요 |

### 32.1 Commit 후 응답이 유실되는 경우

가장 주의해야 할 경계다.

```text
DB Commit 성공
→ Job 100 PROCESSING
→ 네트워크 연결 끊김
→ Worker는 응답을 받지 못함
```

서버는 성공했지만 호출자는 실패로 인식한다. Worker가 즉시 다시 Claim하면 Job 100은 PENDING이 아니므로
다른 Job을 받을 수 있다. Job 100은 Lease가 만료될 때까지 PROCESSING으로 남는다.

현재 구현에는 Claim 요청 Idempotency Key가 없으므로 같은 요청 재전송으로 기존 Claim 응답을 복구하지
못한다. 후속 설계에서는 다음 대안을 검토할 수 있다.

- Worker가 응답을 받은 즉시 로컬 실행 Queue에 안전하게 기록
- Claim 요청 ID를 별도로 받아 동일 요청 결과 재조회
- Worker별 미완료 Claim 조회 API
- 짧은 초기 Lease와 실행 시작 후 Lease 연장

### 32.2 Transaction Rollback이 행 잠금을 해제하는 이유

PostgreSQL 행 잠금은 Transaction에 귀속된다. 애플리케이션이 명시적으로 Unlock SQL을 실행하지 않아도
Commit 또는 Rollback에서 해제된다.

```text
예외 발생
→ Spring TransactionInterceptor가 Rollback
→ PostgreSQL Transaction 종료
→ 행 잠금 자동 해제
```

예외 경로마다 수동 Unlock을 구현하지 않은 이유다.

## 33. 상태 필드별 의미와 함께 갱신하는 이유

Claim이 변경하는 필드는 서로 중복이 아니라 각각 다른 질문에 답한다.

| 필드 | 답하는 질문 |
|---|---|
| `status` | Job이 현재 어느 처리 단계인가? |
| `locked_by_worker_id` | 어느 Worker 실행 인스턴스가 소유하는가? |
| `claim_token` | 해당 Worker의 어떤 Claim인가? |
| `locked_at` | 현재 Lease가 언제 시작됐는가? |
| `lock_expires_at` | 현재 Lease가 언제 끝나는가? |
| `started_at` | Job 전체 처리가 최초로 언제 시작됐는가? |

필드를 따로 갱신하면 중간 상태가 생길 수 있다.

```text
status = PROCESSING
locked_by_worker_id = null
claim_token = null
```

이 상태에서는 처리 중이라는 사실은 알지만 소유자와 복구 기준을 알 수 없다. Entity의 `claim()` 메서드가
관련 필드를 함께 변경하도록 모은 이유다.

### 33.1 `locked_at`과 `started_at`을 분리한 이유

최초 Claim에서는 두 값이 같다.

```text
started_at = 15:00
locked_at = 15:00
```

향후 Lease 만료 후 재Claim되면 의미가 달라진다.

```text
최초 처리 시작
started_at = 15:00

두 번째 Lease 시작
locked_at = 15:10
```

`started_at`을 덮어쓰면 Job이 전체적으로 얼마나 오래 걸렸는지 알 수 없다. 따라서 Entity는 값이 null일
때만 최초 시작 시각을 기록한다.

## 34. API가 Worker ID를 Query Parameter로 받는 이유와 한계

현재 Endpoint는 다음과 같다.

```http
POST /admin/indexing-jobs/claim?workerId=7
```

Worker ID는 DB의 `worker_nodes.id`를 직접 참조하고, Claim된 Job의 `locked_by_worker_id` Foreign Key에
저장하기 때문에 명시적인 입력으로 받는다.

현재 `/admin/**` 보안 정책이 호출자를 보호하므로 일반 사용자는 임의의 Worker ID로 Claim할 수 없다.
하지만 Worker ID 자체는 인증 증명이 아니다.

```text
ADMIN 권한 보유 호출자
→ 다른 Worker ID를 전달할 수 있음
```

운영에서 Worker가 직접 이 API를 호출한다면 다음 단계가 필요하다.

- Worker 전용 Machine Credential
- Credential과 instanceId 또는 workerId 바인딩
- 요청 Worker ID와 인증 주체 일치 검증
- Claim Token 로그 마스킹
- 내부 네트워크 또는 전용 Endpoint 분리

현재 Endpoint는 Worker 처리 기반을 검증하기 위한 관리자 보호 API다. Worker 전용 인증이 추가되면 URL과
권한 정책을 별도로 분리하는 것이 적절하다.

## 35. 실제 OpenSQL 동시성 테스트의 내부 구조

메서드를 두 번 순서대로 호출하는 테스트는 동시성 보장을 검증하지 못한다.

```text
호출 1 완료
→ 호출 2 시작
```

두 호출이 같은 Transaction이나 같은 Thread에서 실행되면 행 잠금 경쟁이 생기지 않는다. 실제 테스트는
다음 구조를 사용한다.

```text
ExecutorService Thread 1
→ REQUIRES_NEW Transaction A

ExecutorService Thread 2
→ REQUIRES_NEW Transaction B
```

### 35.1 잠긴 행을 건너뛰는 테스트

```text
1. Transaction A가 최우선 Job을 SELECT FOR UPDATE한다.
2. CountDownLatch로 행 잠금 획득을 메인 Thread에 알린다.
3. Transaction A는 두 번째 Latch를 기다려 Commit을 지연한다.
4. Transaction B가 같은 Repository 쿼리를 실행한다.
5. Transaction B가 다음 Job을 반환하는지 확인한다.
6. Transaction A의 Latch를 해제하고 Commit한다.
```

Latch 없이 두 Future만 제출하면 첫 Transaction이 너무 빨리 Commit해 실제로 잠금이 겹치지 않을 수 있다.
테스트가 우연히 통과하는 것을 막기 위해 잠금 보유 시점을 명시적으로 제어한다.

### 35.2 하나의 Job을 동시에 Claim하는 테스트

```text
1. ACTIVE Worker 두 개를 저장한다.
2. PENDING Job 하나를 저장한다.
3. 두 Thread가 CyclicBarrier에서 모두 대기한다.
4. Barrier를 동시에 해제한다.
5. 두 Worker가 Service Claim을 호출한다.
6. 성공 Optional 한 건과 빈 Optional 한 건을 확인한다.
7. DB의 PROCESSING, Worker, Token, Lease를 확인한다.
8. LOCKED 이벤트가 한 건인지 확인한다.
```

Service 반환값만 확인하지 않고 최종 DB 상태를 함께 확인해야 두 응답 중 하나가 비어 있어도 내부 상태가
중복 저장되지 않았음을 증명할 수 있다.

### 35.3 격리 스키마와 pgvector Search Path

통합 테스트는 전용 스키마를 사용한다.

```text
docgrid_embedding_job_claim_test
```

테이블은 격리 스키마에 만들지만 pgvector Extension의 `vector` 타입과 연산자는 `public` 스키마에 있다.
따라서 테스트 JDBC Search Path는 다음 순서를 사용한다.

```text
currentSchema=${TEST_DB_SCHEMA},public
```

첫 스키마는 테스트 데이터 격리를 유지하고, 두 번째 `public`은 V32 Migration이 pgvector 타입을 찾게
한다.

## 36. 실제 코드 호출 관계

```text
IndexingJobAdminController
  └─ EmbeddingJobClaimService
      ├─ WorkerNodeRepository
      │   └─ Worker 존재 조회
      ├─ WorkerNode.resolveEffectiveStatus
      │   └─ Heartbeat 기반 실질 상태 계산
      ├─ EmbeddingJobRepository
      │   └─ PENDING Queue 행 잠금
      ├─ EmbeddingJob.claim
      │   └─ 상태와 Lease 소유권 변경
      ├─ IndexingEventRepository
      │   └─ LOCKED 이벤트 저장
      └─ EmbeddingJobConverter
          └─ ClaimedEmbeddingJobResponse 생성
```

각 계층의 경계는 다음과 같다.

```text
Controller
→ HTTP, Security 결과, 상태 코드

Service
→ Use Case 순서와 Transaction

Entity
→ 상태 전이 불변식

Repository
→ DB 쿼리와 행 잠금

Converter / DTO
→ 외부 응답 계약
```

Controller가 Repository를 직접 호출하지 않고 Service가 흐름을 조정하는 이유는 행 잠금부터 이벤트
저장까지 하나의 Transaction 경계를 명확히 유지하기 위해서다.
