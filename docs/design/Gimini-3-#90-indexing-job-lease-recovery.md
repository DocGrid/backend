# Issue #90 인덱싱 Job Lease 갱신 및 만료 복구 상세 설계

closes #90

## 1. 문서 목적

이 문서는 이슈 [#90](https://github.com/DocGrid/backend/issues/90)의 구현 기준을 정의한다.

현재 인덱싱 파이프라인은 Claim, Attempt 시작, 파싱·Chunk 저장, Embedding 저장, 성공 완료와 협력적
실패 보고를 지원한다. 각 실행 단계는 Worker ID, Claim Token과 Lease를 검증하므로 과거 Worker가 늦게
보낸 결과를 거부할 수 있다.

그러나 Worker가 Claim 이후 비정상 종료하거나 실패 API를 호출하기 전에 Lease가 끝나면 다음 상태가
영구적으로 남을 수 있다.

~~~text
EmbeddingJob = PROCESSING
EmbeddingJobAttempt = STARTED 또는 없음
lock_expires_at <= now
~~~

PENDING Job만 기존 Claim API의 후보가 되므로 이 Job은 새 Worker가 다시 가져갈 수 없다. 반대로 만료
Job을 무조건 회수하면 처리 시간이 긴 정상 Worker까지 중복 실행시킬 수 있다. 따라서 정상 Worker의
Lease 갱신과 만료 소유권 회수를 하나의 생명주기 계약으로 구현한다.

### 1.1 성공 기준

- 현재 소유권을 가진 살아 있는 Worker만 만료 전에 Lease를 갱신한다.
- DEAD 판정만으로 유효한 Lease를 조기 회수하지 않는다.
- 만료된 PROCESSING Job만 복구 대상이 된다.
- 여러 Scheduler가 같은 후보를 보더라도 한 Transaction만 상태를 변경한다.
- 현재 Claim의 STARTED Attempt가 있으면 정확히 한 번 실패 종료한다.
- Attempt 시작 전 Worker가 죽은 경우 가짜 Attempt를 만들지 않고 Job만 복구한다.
- 기존 Retry Backoff와 최종 실패 정책을 협력적 실패와 동일하게 사용한다.
- 완료·실패·갱신·복구가 경쟁해도 하나의 전이만 Commit된다.
- 과거 Claim Token으로 후속 Chunk·Embedding·완료·실패 요청을 수행할 수 없다.
- 한 Job의 복구 실패가 같은 Batch의 다른 Job 복구를 막지 않는다.

## 2. 범위

### 2.1 포함 범위

- 관리자용 Lease 갱신 API
- 현재 Job 소유권, Lease와 Worker 생존 상태 검증
- Heartbeat 만료 Worker의 조건부 DEAD 확정
- 만료 PROCESSING Job 후보 ID Batch 조회
- 후보별 독립 Transaction과 PostgreSQL FOR UPDATE SKIP LOCKED
- STARTED Attempt의 Worker Lease 만료 실패 종료
- Attempt가 없는 Claim 만료 복구
- 기존 Retry 지연 계산과 최종 실패 전이 공통화
- Retry 시 과거 Worker·Claim Token·Lease 제거
- 최종 실패 시 Version·Document·Embedding 상태 정리
- LEASE_EXPIRED, RETRY와 FAILED 이벤트 저장
- Scheduler 주기와 Batch 크기 설정
- 단위, Controller, 실제 PostgreSQL과 동시성 검증

### 2.2 제외 범위

- Worker 자동 Job Polling Loop와 실행 슬롯 관리
- Lease 자동 갱신 Scheduler
- Claim 직후 로컬 실행 Queue 영속화
- 관리자 수동 재시도·취소 API
- Retry Jitter
- Chunk·Embedding 단위 Checkpoint와 부분 재개
- 실패 Dashboard, Metric과 Alert
- Worker 전용 Machine Credential과 인증 Principal 바인딩
- Message Broker와 외부 분산 Lock
- 완료된 과거 Version의 수동 재활성화

자동 Polling이 아직 없으므로 이번 기능은 Lease 갱신 Endpoint까지만 제공한다. 향후 Poller는 처리 중인
실행 슬롯별로 이 Endpoint를 호출해야 하며, 사용 가능한 실행 슬롯 수만큼만 Job을 Claim해야 한다.

## 3. 현재 기준선

### 3.1 Claim 소유권

Embedding Job은 Claim 시 다음 값을 한 상태 전이로 기록한다.

~~~text
status = PROCESSING
locked_by_worker_id = 현재 Worker
claim_token = 현재 Claim UUID
locked_at = 최초 Claim 시각
lock_expires_at = 최초 Claim 시각 + leaseDuration
~~~

DB 행 잠금은 Claim Transaction 안의 중복 선택만 막는다. Commit 이후 소유권은 Worker ID, Claim Token과
Lease가 함께 증명한다.

### 3.2 완료와 협력적 실패

완료와 실패 Service는 Job을 가장 먼저 쓰기 잠근다.

~~~text
Job → Version → Document
~~~

최초 상태 전이에서는 PROCESSING 상태, Worker ID, Claim Token과 유효 Lease를 검증한다. 완료 재생이나
실패 재생은 이미 저장된 실행 결과를 확인하므로 각 기능의 기존 멱등 규칙을 따른다.

협력적 실패는 Worker가 유효한 Lease 안에서 실패 유형과 오류 메시지를 보고하는 흐름이다. Retry 가능
유형이면 Job을 지연 PENDING으로 돌리고, 재시도 횟수를 소진했거나 영구 실패면 Job과 Version을 최종
FAILED로 전환한다.

### 3.3 Worker 상태

Worker는 ACTIVE, IDLE, DEAD, STOPPED 상태를 가진다. 관리자 조회는 lastHeartbeatAt과 deadThreshold로
실질 DEAD를 계산하지만 DB 상태를 변경하지 않는다. Heartbeat UPDATE는 ACTIVE와 IDLE에만 성공한다.

이번 기능은 같은 기준을 조건부 UPDATE에 적용해 DEAD를 DB 상태로 fencing한다. 한 번 DEAD로 확정된
Worker는 늦은 Heartbeat와 Lease 갱신으로 다시 살아나지 않는다.

## 4. 핵심 결정

### 4.1 Lease 갱신과 만료 회수는 같은 경계를 사용한다

만료 경계는 다음과 같다.

~~~text
now < lock_expires_at  → 갱신과 현재 Worker 실행 허용
now >= lock_expires_at → 갱신 거부, 복구 허용
~~~

정확히 만료 시각과 같은 순간부터 기존 소유권은 유효하지 않다. 갱신 Service와 복구 Repository가 같은
비교 규칙을 사용해야 경계 순간에 두 소유자가 모두 유효하거나 모두 무효가 되는 공백이 없다.

새 만료 시각은 **renewedAt + leaseDuration**으로 계산한다. 기존 만료 시각에 Lease 기간을 더하면 짧은
간격의 재전송으로 만료 시각을 과도하게 미래로 밀 수 있다. 최초 Claim 시각인 lockedAt은 감사와 전체
실행 시간 기준이므로 변경하지 않는다.

### 4.2 DEAD만으로 유효한 Lease를 회수하지 않는다

Heartbeat는 DB 연결 지연, Scheduler 일시 정지와 GC Pause로 늦어질 수 있다. DEAD 판정 직후 아직 유효한
Lease까지 회수하면 기존 Worker와 새 Worker가 같은 Job을 동시에 처리할 수 있다.

~~~text
Worker DEAD + Lease 유효   → 갱신 차단, 만료까지 회수 대기
Worker ACTIVE + Lease 만료 → 갱신 누락이므로 회수
Worker DEAD + Lease 만료   → 회수
~~~

따라서 Worker 상태는 갱신 자격과 운영 진단에 사용하고, Job 소유권 회수의 최종 기준은 Lease 만료다.

### 4.3 후보 Snapshot과 복구 판정을 분리한다

Scheduler가 긴 Transaction에서 Batch 전체를 잠그면 한 Job의 오류가 전체 Batch를 Rollback시키고, 많은
행 잠금이 완료·실패 요청을 지연시킨다.

복구는 다음 두 단계로 나눈다.

~~~text
1. 만료 후보 Job ID Snapshot 조회
2. 각 ID를 독립 Transaction에서 잠금·재검증·복구
~~~

후보 조회는 잠금 없는 작업 분배용 Snapshot이다. 여러 Scheduler의 결과가 겹칠 수 있으며 조회 직후
Lease가 갱신되거나 Job이 완료될 수도 있다. 정확성은 각 Job Transaction의 다음 쿼리가 보장한다.

~~~sql
SELECT job.*
FROM embedding_jobs job
WHERE job.id = :jobId
  AND job.status = 'PROCESSING'
  AND job.lock_expires_at <= :recoveredAt
FOR UPDATE SKIP LOCKED
~~~

잠긴 행, 이미 완료·실패한 행과 갱신돼 더 이상 만료되지 않은 행은 빈 결과로 건너뛴다.

### 4.4 Job 하나가 Transaction 하나다

후보별 복구 메서드는 REQUIRES_NEW Transaction에서 실행한다. Scheduler는 각 결과를 독립적으로 받고
한 건이 실패해도 다음 후보를 계속 호출한다.

~~~text
Job A 복구 Commit
Job B 불변식 오류 Rollback
Job C 복구 Commit
~~~

Job B는 다음 Scheduler 주기에 다시 후보로 조회된다. 오류 Job을 자동 추정하거나 임의 상태로 바꾸지
않아 데이터 손상을 숨기지 않는다.

### 4.5 Attempt가 없으면 합성하지 않는다

Claim과 Attempt 시작은 별도 API다.

~~~text
Claim Commit
→ Worker Crash
→ Attempt 시작 API 미호출
~~~

이 실행에는 실제 STARTED Attempt가 없으므로 복구기가 새 Attempt를 만들어 과거 실행을 꾸미지 않는다.
Job의 retryCount와 LEASE_EXPIRED Event가 Claim 상실을 기록한다.

현재 Claim Token의 Attempt가 존재하면 STARTED만 허용한다. SUCCESS 또는 FAILED Attempt와 PROCESSING
Job의 조합은 기존 실행 결과와 현재 Snapshot이 모순이므로 자동 보정하지 않고 Rollback한다.

### 4.6 실패 전이 정책을 공통화한다

협력적 실패와 Lease 만료는 실패 발생 원인은 다르지만 Retry와 최종 상태 정책이 같다.

- Retry Backoff 계산
- Version과 Document 잠금·연관 검증
- Retry 시 Version 상태 유지
- 최종 실패 시 Version FAILED
- ACTIVE Embedding STALE
- 기존 검색 가능한 current Version 유지
- 검색 가능한 Version이 없을 때 Document FAILED
- 단계 실패, RETRY와 FAILED Event

이 로직을 두 Service에 복사하면 한쪽만 수정됐을 때 상태 계약이 갈라진다. Job이 먼저 잠긴 Transaction
안에서 호출되는 내부 실패 전이 Component로 분리한다. 외부 실패 API는 요청 검증과 멱등 재생을, Lease
복구 Service는 만료와 현재 Claim Snapshot 검증을 담당한다.

## 5. Lease 갱신 API

### 5.1 Endpoint

~~~http
POST /admin/indexing-jobs/{jobId}/lease/renew
Content-Type: application/json
Authorization: Bearer {admin-token}
~~~

기존 /admin/** ADMIN 정책을 재사용한다. 일반 사용자와 미인증 요청을 허용하지 않는다.

### 5.2 요청

~~~json
{
  "workerId": 7,
  "claimToken": "34c19d16-6ae1-4f6a-a35d-0123456789ab"
}
~~~

| 필드 | 검증 |
| --- | --- |
| jobId | Path 양수 |
| workerId | 필수, 양수 |
| claimToken | 필수, canonical UUID, 최대 36자 |

Claim Token은 소유권 증명에만 사용하며 응답, Event와 일반 로그에 노출하지 않는다.

### 5.3 응답

~~~json
{
  "jobId": 101,
  "workerId": 7,
  "renewedAt": "2026-08-03T15:00:00",
  "lockExpiresAt": "2026-08-03T15:05:00"
}
~~~

최초 요청과 반복 갱신은 모두 200 OK다. 반복 호출은 현재 시각을 기준으로 새 만료 시각을 계산하므로
멱등 재생이 아니라 현재 소유권의 정상 갱신이다.

### 5.4 Transaction

~~~text
1. Clock에서 renewedAt 한 번 계산
2. Job 쓰기 잠금
3. PROCESSING, Worker ID, Claim Token, 현재 Lease 검증
4. Worker 쓰기 잠금
5. Worker 상태와 Heartbeat 검증
6. 새 lockExpiresAt 계산
7. Entity 갱신
8. Commit
~~~

Job을 Worker보다 먼저 잠가 완료·실패·복구 흐름과 소유권 직렬화 지점을 통일한다. DEAD 확정은 Worker
행만 조건부 UPDATE하며 Job을 역순으로 잠그지 않는다.

### 5.5 오류 계약

| 조건 | HTTP | 계약 |
| --- | --- | --- |
| Path 또는 Body 형식 오류 | 400 | 기존 공통 입력 오류 |
| Job 없음 | 404 | EMBEDDING_JOB_NOT_FOUND |
| PROCESSING 상태가 아님 | 409 | EMBEDDING_JOB_NOT_PROCESSING |
| Worker 또는 Claim Token 불일치 | 409 | EMBEDDING_JOB_OWNERSHIP_MISMATCH |
| Lease 만료 또는 누락 | 409/500 | 기존 만료·불일치 계약 |
| Worker 없음 | 404 | WORKER_NOT_FOUND |
| Worker DEAD 또는 STOPPED | 409 | 신규 갱신 불가 계약 |
| Heartbeat 만료 | 409 | 신규 갱신 불가 계약 |

내부 소유권 필드 누락은 호출자 충돌이 아니라 저장 데이터 불변식 오류로 처리한다.

## 6. DEAD Worker 확정

### 6.1 조건부 UPDATE

기준 시각은 **deadDeadline = detectedAt - deadThreshold**다.

~~~sql
UPDATE worker_nodes
SET status = 'DEAD',
    updated_at = :detectedAt
WHERE status IN ('ACTIVE', 'IDLE')
  AND (last_heartbeat_at IS NULL OR last_heartbeat_at <= :deadDeadline)
~~~

Heartbeat가 정확히 deadline과 같으면 DEAD다. Heartbeat와 경쟁해 조건이 더 이상 맞지 않으면 갱신 행
수는 0이며 정상적인 경쟁 결과로 본다.

### 6.2 실행 순서

Recovery Scheduler는 한 실행 주기에서 DEAD 확정을 먼저 시도한 뒤 만료 Job 후보를 처리한다. DEAD
확정 실패는 로그로 남기되 만료 Job 회수 시도 자체를 생략하지 않는다. 두 기능은 Job 회수 정확성에서
직접 의존하지 않기 때문이다.

## 7. 만료 Job 복구

### 7.1 후보 조회

~~~sql
SELECT job.id
FROM embedding_jobs job
WHERE job.status = 'PROCESSING'
  AND job.lock_expires_at <= :recoveredAt
ORDER BY job.lock_expires_at ASC,
         job.id ASC
LIMIT :batchSize
~~~

null Lease를 가진 PROCESSING Job은 자동 복구하지 않는다. 소유권 만료 시각을 알 수 없으므로 회수하면
실행 중인 소유자를 침해할 수 있다. 이 데이터는 불변식 오류 로그와 운영 점검 대상으로 남긴다.

### 7.2 Job별 검증

Job 잠금 뒤 다음 값을 확인한다.

- status는 PROCESSING
- lockedByWorker와 ID가 존재
- canonical Claim Token이 존재
- lockedAt과 lockExpiresAt이 존재
- lockExpiresAt은 recoveredAt 이하
- documentVersion 연관과 ID가 존재
- 현재 Claim Token Attempt는 없거나 정확히 한 건
- 존재하는 Attempt는 같은 Job과 Worker, STARTED 상태

후보 Snapshot 이후 갱신돼 lockExpiresAt이 recoveredAt보다 미래면 정상 Skip이다.

### 7.3 내부 실패 정보

~~~text
errorCode = WORKER_LEASE_EXPIRED
errorMessage = Embedding Job Lease가 만료되어 현재 실행을 회수했습니다.
retryable = true
~~~

자유 형식 예외 메시지나 Stack Trace를 Job과 Attempt에 저장하지 않는다.

### 7.4 Retry 전이

남은 Retry가 있으면 다음을 한 Transaction에서 적용한다.

| 대상 | 전이 |
| --- | --- |
| Attempt | STARTED → FAILED, 존재할 때만 |
| Job | PROCESSING → PENDING |
| Version | 현재 단계 유지 |
| Document | 변경 없음 |
| retryCount | 1 증가 |
| nextRetryAt | recoveredAt + 기존 Backoff |
| 소유권 | Worker, Token, lockedAt, lockExpiresAt 제거 |
| Event | LEASE_EXPIRED, 단계 실패, RETRY |

다음 Claim은 새 Worker와 새 Claim Token을 발급하고 Attempt 번호도 기존 규칙대로 증가한다.

### 7.5 최종 실패

Retry를 소진하면 다음을 적용한다.

| 대상 | 전이 |
| --- | --- |
| Attempt | STARTED → FAILED, 존재할 때만 |
| Job | PROCESSING → FAILED |
| Version | 현재 처리 단계 → FAILED |
| 실패 Version Embedding | ACTIVE → STALE |
| Document | 검색 가능한 current Version이 없을 때 FAILED |
| 기존 current Version | INDEXED면 포인터와 검색 가용성 유지 |
| Event | LEASE_EXPIRED, 단계 실패, FAILED |

잠금 순서는 Job → Version → Document다. Embedding 일괄 UPDATE는 Version과 Document 잠금 뒤 수행한다.

### 7.6 Event

LEASE_EXPIRED Event는 상태 전이 원인을 먼저 기록한다.

~~~json
{
  "workerId": 7,
  "attemptId": 21,
  "attemptNo": 2,
  "expiredAt": "2026-08-03T15:00:00",
  "retryCountBefore": 1
}
~~~

Attempt가 없으면 attemptId와 attemptNo를 생략한다. Claim Token은 Metadata에 넣지 않는다.

## 8. Scheduler

### 8.1 설정

~~~yaml
indexing:
  worker:
    lease-recovery-interval: ${INDEXING_WORKER_LEASE_RECOVERY_INTERVAL:30s}
    lease-recovery-batch-size: ${INDEXING_WORKER_LEASE_RECOVERY_BATCH_SIZE:100}
~~~

| 설정 | 기본값 | 검증 |
| --- | --- | --- |
| leaseRecoveryInterval | 30초 | null 아님, 양수 |
| leaseRecoveryBatchSize | 100 | 1 이상 |

기존 indexing.worker.enabled=true 조건에서만 Scheduler를 등록한다.

### 8.2 실행 흐름

~~~text
1. detectedAt 계산
2. DEAD Worker 조건부 확정
3. recoveredAt 계산
4. Batch 크기만큼 만료 후보 ID Snapshot 조회
5. 각 ID를 독립 REQUIRES_NEW Transaction으로 복구
6. 성공·Skip·실패 개수 로그
~~~

한 후보에서 예외가 발생하면 Job ID와 오류 분류만 로그로 남기고 다음 ID를 계속 처리한다. Claim Token,
Chunk 본문, Vector와 내부 Stack Trace 전문은 일반 정보 로그에 포함하지 않는다.

## 9. 동시성

### 9.1 갱신과 복구

| 먼저 Job 잠금을 획득한 흐름 | 결과 |
| --- | --- |
| 갱신, 아직 Lease 유효 | 만료 시각 연장, 복구는 재검증 후 Skip |
| 복구, Lease 만료 | Retry/최종 실패 Commit, 갱신은 상태·Token 불일치 |
| 갱신, 이미 Lease 만료 | 갱신 Rollback, 복구 가능 |

### 9.2 완료와 복구

| 먼저 Job 잠금을 획득한 흐름 | 결과 |
| --- | --- |
| 완료, Lease 유효 | INDEXED Commit, 복구 후보에서 제외 |
| 복구, Lease 만료 | PENDING 또는 FAILED Commit, 완료 요청 거부 |

완료 Service가 잠금을 얻은 시각의 Lease를 검증한다. HTTP 요청 도착 순서가 아니라 Job 잠금 뒤 고정한
기준 시각과 저장 상태가 결과를 결정한다.

### 9.3 협력적 실패와 복구

협력적 실패는 유효 Lease를 요구하므로 정상적으로는 복구와 같은 순간에 둘 다 성공하지 않는다.

- 실패가 만료 전에 Job 잠금을 얻으면 Retry 또는 최종 실패하고 복구는 Skip한다.
- 복구가 만료 뒤 Job 잠금을 얻으면 과거 실패 요청은 상태 또는 Token 불일치로 거부된다.

### 9.4 다중 Scheduler

두 Scheduler가 같은 후보 ID를 읽어도 첫 Transaction만 Job 잠금을 얻는다.

~~~text
Scheduler A → Job 101 FOR UPDATE 성공 → 복구
Scheduler B → Job 101 SKIP LOCKED 또는 상태 재검증 실패 → Skip
~~~

별도 JVM Lock과 분산 Lock을 사용하지 않고 PostgreSQL을 최종 조정 지점으로 유지한다.

## 10. 클래스 책임

| 클래스 | 책임 |
| --- | --- |
| EmbeddingJob | Lease 갱신과 Retry 시 소유권 제거 Domain 전이 |
| EmbeddingJobLeaseCommandService | 갱신 Transaction과 Worker 생존 검증 |
| IndexingFailureTransitionService | Retry·최종 실패 공통 상태 전이 |
| WorkerNodeCommandService | Heartbeat 만료 Worker 조건부 DEAD 확정 |
| EmbeddingJobRecoveryQueryService | 만료 후보 ID Snapshot 조회 |
| EmbeddingJobLeaseRecoveryService | 후보 한 건의 독립 복구 Transaction |
| WorkerLeaseRecoveryScheduler | DEAD 확정과 Batch 복구 순서 조정 |
| IndexingJobAdminController | Lease 갱신 HTTP 입력·응답 계약 |

새 클래스와 Record에는 역할, 책임과 경계를 설명하는 class-level comment를 작성한다. 순차 흐름에는
번호 주석을 유지하고 기존 실패 Service 주석도 공통 전이 위임 구조와 일치하도록 수정한다.

## 11. Migration과 호환성

새 컬럼이나 제약은 필요하지 않다.

- embedding_jobs.lock_expires_at과 관련 Index가 이미 존재한다.
- worker_nodes.status와 last_heartbeat_at Index가 이미 존재한다.
- indexing_events.event_type은 길이 30 문자열이므로 LEASE_EXPIRED를 저장할 수 있다.
- Attempt 오류 필드와 Retry 필드는 이미 존재한다.

따라서 Flyway Migration을 추가하지 않는다.

## 12. 보안

- 기존 /admin/** ADMIN 정책을 재사용한다.
- Request DTO에서 양수 Worker ID와 canonical UUID Claim Token을 검증한다.
- Claim Token은 성공 응답, Error 메시지, Event Metadata와 일반 로그에서 제외한다.
- 자유 형식 내부 예외, 문서 본문과 Vector를 오류 Snapshot에 저장하지 않는다.
- Worker 상태 오류는 내부 인프라 상세를 노출하지 않는 제한된 ErrorCode로 응답한다.
- application.yml에는 환경변수 이름과 안전한 기본값만 추가하며 Secret을 넣지 않는다.

## 13. 테스트 전략

### 13.1 Domain·설정

- PROCESSING Job의 유효한 새 만료 시각 갱신
- PENDING/INDEXED/FAILED Job 갱신 거부
- null 또는 기존 만료 이하의 새 시각 거부
- lockedAt, Worker와 Claim Token 보존
- Recovery 간격 0·음수와 Batch 0 거부

### 13.2 Lease 갱신 Service·Controller

- 현재 Worker·Token·Lease와 살아 있는 Heartbeat 성공
- 다른 Worker와 과거 Token 거부
- 정확히 만료 시각 갱신 거부
- DEAD, STOPPED와 Heartbeat 만료 Worker 거부
- Job → Worker 잠금 호출 순서
- ADMIN 200, USER·미인증 403
- 입력 형식 400, 상태·소유권 409
- 응답과 로그에 Claim Token 없음

### 13.3 DEAD 확정

- deadline 이전 Heartbeat는 유지
- deadline과 같은 Heartbeat는 DEAD
- ACTIVE와 IDLE만 DEAD 전환
- DEAD와 STOPPED는 변경하지 않음
- 늦은 Heartbeat가 DEAD 상태를 되살리지 않음

### 13.4 만료 후보 Repository

- PROCESSING이며 만료된 Job만 조회
- 정확히 만료 시각 포함
- PENDING, INDEXED와 미래 Lease 제외
- lockExpiresAt ASC, id ASC 정렬
- Batch 크기 제한
- 같은 Job의 FOR UPDATE SKIP LOCKED 경쟁

### 13.5 복구 Service

- STARTED Attempt Retry 종료
- Attempt 없는 Claim Retry
- Retry 소진 최종 실패
- 기존 INDEXED current Version 유지
- 검색 가능한 Version이 없는 Document FAILED
- ACTIVE Embedding STALE
- 종료 Attempt와 PROCESSING Job 모순 Rollback
- 소유권 필드 누락 Rollback
- Event 저장 실패 시 전체 Rollback
- 과거 Token의 후속 완료·실패·저장 거부

### 13.6 Scheduler·동시성

- 빈 후보 정상 종료
- Batch 크기만큼만 호출
- 한 Job 예외 뒤 다음 후보 계속 처리
- 두 Scheduler의 같은 후보 복구가 단일 전이로 수렴
- 갱신과 복구 경쟁
- 완료와 복구 경쟁
- 협력적 실패와 복구 경쟁
- Job별 독립 Transaction Rollback

### 13.7 전체 검증

~~~bash
./gradlew test
./gradlew claimConcurrencyTest
./gradlew clean build
git diff --check
~~~

실제 PostgreSQL이 필요한 Repository와 동시성 검증은 OpenSQL 통합 테스트로 실행한다. 실제 애플리케이션
HTTP에서는 Swagger 계약의 Lease 갱신 성공, 입력 오류, 상태·소유권 충돌과 권한을 확인한다. 실행 결과는
docs/test-results/Gimini-3-#90-indexing-job-lease-recovery.md에 기록한다.

## 14. 완료 조건

- Lease 갱신과 복구가 같은 만료 경계를 사용한다.
- 유효한 현재 Worker만 Lease를 갱신한다.
- DEAD Worker는 조건부 UPDATE로 fencing된다.
- DEAD만으로 유효 Lease를 조기 회수하지 않는다.
- 후보 Snapshot은 Job Transaction에서 다시 검증된다.
- Job별 독립 Transaction과 SKIP LOCKED가 중복 회수를 막는다.
- Attempt 존재 여부에 따라 실제 이력만 종료한다.
- Retry와 최종 실패 정책이 협력적 실패와 동일하다.
- 과거 소유권이 Retry 전이에서 완전히 제거된다.
- 완료·실패·갱신·복구 경합이 하나의 상태 전이로 수렴한다.
- 단위, Controller, PostgreSQL, 동시성, 실제 HTTP와 전체 빌드가 통과한다.
- 구현과 검증 문서가 실제 코드·실행 결과와 일치한다.
