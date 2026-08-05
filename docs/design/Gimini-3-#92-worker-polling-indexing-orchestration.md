# Issue #92 Worker 자동 Polling 및 인덱싱 실행 오케스트레이션 상세 설계

closes #92

## 1. 문서 목적

이 문서는 이슈 [#92](https://github.com/DocGrid/backend/issues/92)의 구현 기준을 정의한다.

현재 인덱싱 도메인은 Worker 등록·Heartbeat, Job Claim, Attempt 시작, 문서 Chunk 생성, Embedding 생성,
인덱싱 완료, 협력적 실패 보고, Lease 갱신과 만료 복구를 각각 제공한다. 그러나 각 기능은 관리자 API나
Service 호출 단위로만 연결돼 있어 Worker를 활성화해도 PENDING Job을 자동으로 가져와 끝까지 실행하지
않는다.

이번 작업은 같은 Spring 애플리케이션 안에서 실행되는 Worker가 사용 가능한 로컬 실행 슬롯만큼 Job을
Claim하고, 기존 Service를 직접 조합해 파이프라인을 수행하도록 한다. 외부 파일 저장소와 Embedding
Provider 호출 중에는 DB Transaction을 유지하지 않으며, 현재 Worker ID와 Claim Token은 각 단계의
소유권 검증에만 사용한다.

### 1.1 성공 기준

- Worker 기능이 비활성화된 API 전용 실행에서는 Poller와 실행 Executor가 만들어지지 않는다.
- Worker 등록이 완료된 뒤에만 Job을 Polling한다.
- `max-concurrency`를 초과해 Job을 Claim하거나 실행하지 않는다.
- 실행 슬롯을 먼저 확보한 뒤 한 Job만 Claim하고, Job이 없거나 제출에 실패하면 슬롯을 즉시 반환한다.
- Claim한 Job은 Attempt 시작, Chunk 생성, Embedding 생성, 완료 순서로 처리한다.
- 이미 진행된 문서 버전은 현재 상태에 맞는 단계부터 안전하게 재개한다.
- 외부 I/O 중 DB 행 잠금이나 장기 Transaction을 유지하지 않는다.
- 실행 중인 Job은 설정된 주기로 Lease를 갱신하고 종료 시 갱신 작업을 해제한다.
- Attempt 시작 이후의 실행 오류는 제한된 실패 유형과 안전한 메시지로 실패 Service에 보고한다.
- 소유권·Lease를 이미 잃은 실행은 과거 Claim으로 실패 상태를 덮어쓰지 않는다.
- 애플리케이션 종료 시 신규 Polling을 먼저 중단하고, 제한 시간 동안 실행 중인 작업을 기다린 뒤 Worker를
  STOPPED 처리한다.
- Claim Token, 원문 내용, Vector와 외부 인증 정보는 일반 로그에 남기지 않는다.
- 단위 테스트와 실제 PostgreSQL 동시성 테스트로 실행 상한, Lease, 종료와 단일 Claim을 검증한다.

## 2. 범위

### 2.1 포함 범위

- Polling 주기, 최대 동시 실행 수, Lease 갱신 주기와 종료 유예 시간 설정
- 고정 크기·무대기 Job Executor와 단일 Lease Scheduler
- 로컬 실행 슬롯 예약·반환
- Worker 등록 완료 여부를 기준으로 한 Polling
- 기존 Claim Service의 반복 호출
- 문서 버전 상태 조회와 단계 재개 결정
- Attempt, Chunk, Embedding, 완료 Service 직접 오케스트레이션
- 실행별 Lease 갱신 등록·해제와 소유권 상실 표시
- 예외별 실패 유형 분류와 안전한 오류 메시지 생성
- Attempt 시작 이후의 협력적 실패 보고
- 신규 Claim 차단, 실행 대기, 강제 중단 순서의 Graceful Shutdown
- 단위, Spring Context, 실제 PostgreSQL과 동시성 검증

### 2.2 제외 범위

- Message Broker, 외부 작업 Queue와 분산 Lock
- Claim한 로컬 작업의 영속 Queue
- Worker 수에 따른 자동 확장
- Chunk·Embedding 단위 Checkpoint와 부분 Batch 재개
- Retry Jitter와 관리자 수동 재시도·취소 API
- Worker 전용 Machine Credential
- 실행 Dashboard, Metric, Alert와 분산 Trace
- 외부 Worker 프로세스를 위한 HTTP Client
- 파일 형식과 Embedding 모델 정책 변경

이번 Worker는 기존 Service들과 같은 Spring Context에서 동작한다. 자기 자신에게 관리자 HTTP 요청을
보내면 인증·직렬화·네트워크 실패 지점만 추가되므로 내부 Service를 직접 호출한다. 외부 프로세스 Worker가
필요해지는 시점에 별도 Client 계약을 설계한다.

## 3. 현재 기준선

### 3.1 Worker 생명주기

`WorkerLifecycleManager`는 애플리케이션 준비 이벤트에서 Worker를 등록하고 현재 Worker ID를 메모리에
보관한다. Heartbeat Scheduler는 Worker ID가 존재할 때만 갱신한다. Context 종료 이벤트에서는 Worker를
STOPPED로 변경하고 메모리의 ID를 제거한다.

Poller는 이 ID를 등록 완료 신호로 사용한다. 등록 전에는 Claim Service를 호출하지 않으며 등록이 실패해
ID가 없으면 다음 주기에도 아무 작업을 하지 않는다.

### 3.2 Claim과 실행 소유권

Claim Service는 PostgreSQL `FOR UPDATE SKIP LOCKED`로 다음 PENDING Job을 하나 선택하고 아래 값을 한
Transaction에서 기록한다.

~~~text
status = PROCESSING
locked_by_worker_id = 현재 Worker
claim_token = 새 UUID
locked_at = Claim 시각
lock_expires_at = Claim 시각 + leaseDuration
~~~

각 후속 Service는 Job 행을 먼저 잠그고 Worker ID, Claim Token과 유효 Lease를 다시 검증한다. 로컬 실행
상태는 편의를 위한 조정 정보일 뿐이며, 결과 저장의 최종 권한은 DB 소유권 검증이 결정한다.

### 3.3 단계별 Transaction 경계

Chunk와 Embedding Service는 준비 Transaction과 완료 Transaction 사이에서 외부 I/O를 수행한다.

~~~text
짧은 준비 Transaction
→ Transaction 밖 파일 읽기·파싱 또는 Embedding HTTP 호출
→ 짧은 완료 Transaction
~~~

오케스트레이터에는 `@Transactional`을 적용하지 않는다. 기존 Service가 가진 짧은 Transaction 경계를
그대로 사용해 Object Storage와 Embedding Provider 응답을 기다리는 동안 DB 잠금을 유지하지 않는다.

### 3.4 단계 재생 범위

- Chunk Service는 `UPLOADED`, `PARSING`에서 작업하고 `CHUNKED` 결과를 재생한다.
- Embedding Service는 `CHUNKED`, `EMBEDDING`에서 작업 또는 결과를 재생한다.
- 완료 Service는 `EMBEDDING`의 완전한 결과를 `INDEXED`로 확정하고 같은 실행의 완료를 재생한다.

Chunk Service는 `EMBEDDING` 상태를 재생하지 않는다. 따라서 모든 실행에서 Chunk를 무조건 다시 호출하지
않고 현재 문서 버전 상태를 조회해 시작 단계를 선택해야 한다.

## 4. 핵심 결정

### 4.1 실행 슬롯을 Claim보다 먼저 확보한다

동시 실행 상한은 Executor Queue 크기가 아니라 명시적인 슬롯으로 관리한다.

~~~text
1. 로컬 슬롯 확보
2. 등록된 Worker ID 확인
3. Job 하나 Claim
4. Executor에 즉시 제출
5. 작업 종료 또는 중간 실패 시 슬롯 반환
~~~

슬롯이 없으면 Claim Service를 호출하지 않는다. Claim 후 Queue에서 오래 기다리며 Lease를 소모하는 상황을
막기 위해 Job Executor는 고정 Thread 수와 `SynchronousQueue`를 사용한다. 즉시 실행할 Thread가 없으면
제출을 거부하고 슬롯을 반환한다. 정상 설계에서는 슬롯 수와 Thread 수가 같으므로 제출 거부는 종료 경쟁이나
내부 불변식 오류일 때만 발생한다.

한 Polling 주기에는 실행 가능한 슬롯 수만큼 위 절차를 반복한다. 첫 빈 Claim을 만나면 현재 PENDING
후보가 없다고 판단해 그 주기의 반복을 끝낸다.

### 4.2 단계는 문서 버전 상태로 재개한다

Attempt를 시작한 뒤 Claim 응답의 `documentVersionId`로 현재 상태를 조회한다.

| 현재 상태 | 실행 |
| --- | --- |
| UPLOADED | Chunk 생성 → Embedding 생성 → 완료 |
| PARSING | Chunk 재개 → Embedding 생성 → 완료 |
| CHUNKED | Embedding 생성 → 완료 |
| EMBEDDING | Embedding 재개 → 완료 |
| INDEXED | PROCESSING Job과 모순이므로 실패 보고 |
| FAILED | 실행 대상이 아니므로 실패 보고 |

상태 조회는 경로 선택용 Snapshot일 뿐이다. 조회 직후 상태가 바뀔 수 있으므로 각 단계 Service의 Job 잠금,
Attempt와 소유권 검증이 최종 정확성을 보장한다. 오케스트레이터가 JPA Entity를 단계 사이에 보관하지
않는다.

### 4.3 Claim과 Attempt 사이 오류는 합성 실패를 만들지 않는다

Claim 성공 후 Attempt 시작 전에 프로세스가 종료되거나 Attempt Service가 실패할 수 있다. 이때 실제
Attempt ID가 없으므로 실패 Service를 호출하거나 가짜 Attempt를 만들지 않는다. Lease 갱신도 시작하지
않고 소유권 만료 복구가 Job을 회수하게 둔다.

Attempt가 시작된 뒤 발생한 오류만 현재 Attempt ID로 협력적 실패를 보고한다.

### 4.4 실패 분류는 제한된 계약만 저장한다

예외 메시지와 Stack Trace를 그대로 DB에 저장하면 원문, Object Key, 외부 Endpoint나 인증 정보가 섞일 수
있다. 분류기는 `DocGridException.errorCode`를 허용 목록으로 매핑하고 고정된 안전 메시지를 만든다.

| 원인 | 실패 유형 | Retry |
| --- | --- | --- |
| FILE_STORAGE_FAILED | STORAGE_UNAVAILABLE | 가능 |
| 지원하지 않는 형식, 빈 내용, UTF-8 해석 실패 | DOCUMENT_CONTENT_INVALID | 불가 |
| EMBEDDING_SERVER_UNAVAILABLE | EMBEDDING_PROVIDER_UNAVAILABLE | 가능 |
| 차원 불일치, 잘못된 Vector | EMBEDDING_RESULT_INVALID | 불가 |
| 상태·연관·Chunk·Embedding 불변식 오류 | INDEXING_STATE_INCONSISTENT | 불가 |
| 그 밖의 실행 오류 | WORKER_INTERNAL_ERROR | 가능 |

다음 오류는 이미 현재 실행의 권한을 잃었음을 뜻하므로 실패 보고를 시도하지 않는다.

- EMBEDDING_JOB_NOT_FOUND
- EMBEDDING_JOB_NOT_PROCESSING
- EMBEDDING_JOB_OWNERSHIP_INVALID
- EMBEDDING_JOB_LEASE_EXPIRED
- EMBEDDING_JOB_ATTEMPT_INVALID
- EMBEDDING_JOB_FAILURE_CONFLICT

이 경우 현재 실행은 중단하고 Lease 복구 또는 이미 완료된 경쟁 실행의 결과를 따른다. 실패 보고 자체가
실패해도 원래 예외를 숨기지 않으며 Claim Token 없이 Job ID, Attempt ID와 오류 코드만 로그에 남긴다.

### 4.5 Lease 갱신은 실행별 Handle로 관리한다

Attempt 시작 직후 실행별 Lease 갱신 Handle을 만든다. 하나의 Scheduled Executor가 각 활성 실행의 갱신을
예약하며 갱신 요청은 기존 `EmbeddingJobLeaseService`를 직접 호출한다.

~~~text
Attempt 시작
→ 즉시 Lease Handle 등록
→ lease-renewal-interval마다 갱신
→ 각 단계 전후 현재 소유권 확인
→ 완료·실패·중단의 finally에서 Handle 해제
~~~

갱신이 소유권·상태 충돌로 거부되면 Handle을 `lost` 상태로 바꾸고 이후 갱신을 취소한다. 파이프라인은 단계
사이에서 이를 확인해 다음 외부 작업을 시작하지 않는다. 일반 인프라 예외는 로그에 기록하고 다음 예약을
유지한다. 실제 결과 저장 전에는 기존 Service가 DB Lease를 다시 검증하므로 갱신 실패가 소유권을 연장한
것처럼 취급되지 않는다.

갱신 주기는 0보다 크고 Lease 기간보다 짧아야 한다. 기본값은 Lease 5분, 갱신 1분이다.

### 4.6 종료는 Polling 중단 후 Worker 정지 순서다

Context 종료 이벤트 Listener 순서를 명시한다.

~~~text
1. Poller가 신규 슬롯 확보와 Claim 중단
2. Job Executor shutdown
3. shutdown-grace-period 동안 활성 실행 완료 대기
4. 시간 초과 시 실행 Thread interrupt와 남은 Lease Handle 취소
5. WorkerLifecycleManager가 Worker를 STOPPED 처리
6. 완료되지 않은 PROCESSING Job은 Lease 만료 복구가 회수
~~~

실행 대기 중 Worker ID와 Heartbeat를 유지해야 진행 중인 작업이 완료·실패와 Lease 갱신을 수행할 수 있다.
따라서 실행 종료 Listener를 높은 우선순위로, Worker STOPPED Listener를 낮은 우선순위로 둔다. 유예 시간이
끝난 뒤 DB 상태를 임의로 실패 처리하지 않는다. 외부 I/O가 interrupt에 반응하지 않더라도 만료된 Lease가
과거 결과 저장을 차단한다.

## 5. 구성 요소 설계

### 5.1 설정과 실행 기반

`IndexingWorkerProperties`에 다음 값을 추가한다.

| 설정 | 기본값 | 검증 |
| --- | --- | --- |
| polling-interval | 1초 | 양수 |
| max-concurrency | 2 | 1 이상 |
| lease-renewal-interval | 1분 | 양수, lease-duration보다 짧음 |
| shutdown-grace-period | 30초 | 0 이상 |

종료 유예 시간은 0을 허용해 즉시 중단 정책을 표현한다. 나머지 주기는 0 또는 음수를 허용하지 않는다.

`WorkerExecutionConfig`는 Worker 활성화 조건에서만 다음 Bean을 제공한다.

- `workerJobExecutor`: `max-concurrency` 고정 Thread, `SynchronousQueue`, AbortPolicy
- `workerLeaseScheduler`: 단일 Scheduled Thread, 취소 작업 즉시 제거

Thread 이름에는 역할과 번호만 포함하고 Worker 이름, Job ID와 Claim Token을 포함하지 않는다.

### 5.2 실행 슬롯

`WorkerExecutionSlotPool`은 `Semaphore(max-concurrency)`와 신규 작업 허용 상태를 소유한다. 획득 성공 시
한 번만 닫을 수 있는 `WorkerExecutionSlot`을 반환한다. Claim 없음, 제출 거부, 파이프라인 종료가 같은
슬롯을 중복 반환하지 않도록 Slot 자체가 원자적인 closed 상태를 가진다.

### 5.3 상태 조회

`DocumentIndexingStageQueryService`는 `documentVersionId`로 `DocumentVersionStatus`만 반환한다. 조회 결과는
분기용 Snapshot이며 Entity를 Worker 계층에 노출하지 않는다. Version이 없으면 기존 문서 상태 불변식 오류로
처리한다.

### 5.4 파이프라인

`WorkerIndexingPipeline`은 다음 의존성을 조합한다.

- EmbeddingJobAttemptService
- DocumentIndexingStageQueryService
- DocumentParsingService
- DocumentEmbeddingService
- DocumentIndexingCompletionService
- WorkerIndexingFailureReporter
- WorkerLeaseRenewalManager

실행 순서는 다음과 같다.

~~~text
1. Attempt 시작
2. Lease 갱신 Handle 등록
3. 현재 Version 상태 조회
4. 필요한 경우 Chunk 생성 또는 재개
5. 필요한 경우 Embedding 생성 또는 재개
6. 인덱싱 완료
7. 오류 시 실패 분류·보고
8. finally에서 Lease Handle과 실행 슬롯 해제
~~~

Pipeline 메서드에는 Transaction을 적용하지 않는다. Claim Token은 요청 DTO 생성에만 전달하고 로그나 결과
객체의 `toString()` 출력에 포함하지 않는다.

### 5.5 Poller와 실행 관리자

`WorkerJobPollingScheduler`는 고정 지연으로 `poll()`을 호출한다. 동시 Scheduler 실행을 막기 위해 현재
Polling 여부를 원자적으로 보호한다. `poll()`은 Worker ID와 남은 슬롯을 확인하고, 확보한 슬롯마다 Claim과
제출을 한 번 수행한다.

`WorkerExecutionLifecycleManager`는 Poller 중단과 Executor 종료를 조정한다. 종료 Listener는 여러 번
호출돼도 같은 종료 절차를 반복하지 않는다. InterruptedException을 받으면 현재 Thread의 interrupt 상태를
복구하고 즉시 강제 중단 단계로 이동한다.

## 6. 동시성·오류 계약

### 6.1 여러 Worker의 Polling

각 Worker가 동시에 Polling해도 Claim Repository의 `FOR UPDATE SKIP LOCKED`가 같은 PENDING Job의 중복
선택을 막는다. 로컬 슬롯은 한 프로세스의 실행 상한만 담당하고 전역 동시성 제어로 사용하지 않는다.

### 6.2 종료와 Polling 경쟁

종료 플래그를 변경한 뒤 이미 슬롯을 확보한 Polling Thread가 있을 수 있다. Claim 직전에 허용 상태를 다시
검사하고, Claim 이후 제출이 거부되면 슬롯을 반환한다. 이미 Claim된 Job은 즉시 실행할 수 없으면 Lease
만료 복구 대상으로 남긴다. 소유권을 임의로 반납하는 새 DB 전이는 만들지 않는다.

### 6.3 Lease 갱신과 완료·실패 경쟁

갱신, 완료와 실패는 모두 Job 행을 먼저 잠근다. 먼저 Commit한 상태가 후속 호출의 소유권 검증 결과를
결정한다.

~~~text
갱신 선행 → 새 Lease 안에서 완료·실패 가능
완료 선행 → 갱신은 PROCESSING 아님으로 거부
실패 선행 → 갱신은 PROCESSING 아님 또는 소유권 제거로 거부
복구 선행 → 과거 실행의 후속 결과 저장 거부
~~~

### 6.4 Polling 실패

- Worker 미등록: 조용히 Skip
- 슬롯 없음: 조용히 Skip
- PENDING Job 없음: 정상 종료
- Claim DB 오류: 해당 주기 중단, 다음 주기 재시도
- Executor 제출 거부: 슬롯 반환, Job은 Lease 복구 대기
- 파이프라인 RuntimeException: Attempt 존재 시 제한된 실패 보고

반복 Scheduler 자체가 예외로 중단되지 않도록 Polling 경계에서 RuntimeException을 기록하고 삼킨다.

## 7. 보안·관측성

### 7.1 로그 허용 필드

- workerId
- jobId
- attemptId
- 문서 버전 상태
- 실패 유형 또는 ErrorCode
- 실행 결과와 소요 시간
- 활성 실행 수와 종료 대기 결과

### 7.2 로그 금지 필드

- Claim Token
- 문서 원문과 Chunk Text
- Embedding Vector
- Object Storage Bucket/Object Key
- 외부 Provider 요청·응답 본문
- Authorization Header와 환경 변수 값

예상된 빈 Polling은 로그를 남기지 않는다. Job 시작·완료는 INFO, 소유권 상실과 제출 거부는 WARN,
불변식 오류와 실패 보고 실패는 ERROR를 사용한다.

## 8. 테스트 전략

### 8.1 설정·슬롯 단위 테스트

- 새 설정의 기본값
- 0·음수 Polling/갱신 주기 거부
- Lease 이상 갱신 주기 거부
- 0 미만 종료 유예 시간 거부
- 최대 슬롯까지만 획득
- Slot 중복 close가 Permit을 중복 반환하지 않음
- Poller 중단 뒤 신규 슬롯 획득 거부

### 8.2 파이프라인 단위 테스트

- UPLOADED/PARSING은 Chunk부터 실행
- CHUNKED/EMBEDDING은 Embedding부터 실행
- 완료까지 Service 호출 순서 보장
- Attempt 시작 전 오류는 실패 Service 미호출
- Attempt 시작 후 오류는 분류된 실패 요청으로 보고
- Claim Token이 로그·응답용 객체에 노출되지 않음
- 소유권 상실 오류는 실패 보고 미호출
- 모든 종료 경로에서 Lease Handle과 슬롯 반환

### 8.3 Lease 단위 테스트

- 설정 주기로 갱신 Service 호출
- Handle 종료 시 예약 작업 취소
- 소유권 오류 시 lost 표시와 후속 예약 중단
- 일시적 인프라 오류 뒤 예약 유지
- 완료와 실패 경로에서 활성 Handle 제거

### 8.4 Polling·종료 단위 테스트

- Worker 등록 전 Claim 미호출
- 가용 슬롯 수만큼만 Claim·제출
- 빈 Claim에서 추가 조회 중단
- Claim 예외와 제출 거부 시 슬롯 반환
- 종료 후 신규 Claim 없음
- 유예 시간 안 완료 시 강제 중단 없음
- 유예 시간 초과 시 `shutdownNow`와 Lease Handle 취소
- Worker STOPPED 기록이 실행 종료 대기 뒤 수행됨

### 8.5 실제 PostgreSQL 통합·동시성 테스트

- 여러 Poller가 경쟁해도 한 Job은 한 Worker만 Claim
- 한 Worker의 활성 PROCESSING Job 수가 설정된 최대 동시성 이하
- 장기 실행 중 Lease 갱신으로 만료 복구가 Job을 회수하지 않음
- 갱신 중단 후 만료되면 Recovery가 Job을 한 번만 재예약
- 종료 경쟁에서 완료된 Job은 INDEXED, 미완료 Job은 Lease 만료 뒤 재시도 가능
- 기존 Claim, Attempt, Chunk, Embedding, 완료와 Lease 복구 회귀 테스트 통과

실행된 명령, 환경, 결과와 측정값은 구현 완료 후 `docs/test-results/` 문서에 기록한다.

## 9. 구현 순서

1. 이 상세 설계 문서 확정
2. Worker 실행 설정, Executor와 슬롯 기반 추가
3. 상태 조회와 인덱싱 Pipeline 구현
4. 실패 분류와 보고 연결
5. 실행별 Lease 갱신 관리
6. 슬롯 기반 Polling과 Graceful Shutdown 연결
7. 설정·슬롯·Pipeline·Lease·Polling 단위 테스트
8. 실제 PostgreSQL 통합·동시성 테스트
9. 전체 검증 결과 문서화

각 단계는 독립적으로 빌드 가능한 커밋으로 유지한다. 구현 중 설계 변경이 필요하면 먼저 이 문서의 관련
결정과 테스트 기준을 갱신한다.
