# Issue #39 Worker 등록 및 Heartbeat 상세 설계

closes #39

## 1. 목적

문서 인덱싱은 업로드 요청을 처리하는 API 서버와 실제 파싱·청킹·임베딩을 수행하는 Worker가 분리된
비동기 구조로 동작한다. 이 구조에서 `embedding_jobs`에 처리할 작업이 존재한다는 사실만으로는 어떤
Worker가 현재 실행 중인지, 해당 Worker가 실제로 살아 있는지, 정상적으로 종료했는지를 판단할 수 없다.

Worker의 신원과 생존 상태를 확인하지 않고 Job 소유권을 부여하면 다음 문제가 발생할 수 있다.

```text
이미 종료된 Worker에게 Job을 할당
Heartbeat가 끊긴 Worker를 정상 Worker로 오인
같은 역할명의 여러 Worker 프로세스를 하나로 취급
재시작 전 프로세스와 재시작 후 프로세스를 구분하지 못함
Worker 장애 후 해당 Worker가 보유했던 Job을 복구할 기준이 없음
```

이 이슈의 목적은 Spring Boot 애플리케이션에서 실행되는 각 인덱싱 Worker 프로세스를 DB에 독립적으로
등록하고, 주기적인 Heartbeat와 종료 기록을 통해 Worker의 생명주기를 추적할 수 있는 기반을 만드는
것이다.

최종적으로 시스템은 다음 질문에 답할 수 있어야 한다.

```text
현재 등록된 Worker는 무엇인가?
각 Worker 실행 프로세스의 고유 식별자는 무엇인가?
마지막으로 생존이 확인된 시각은 언제인가?
정상 종료한 Worker인가?
Heartbeat가 끊겨 실질적으로 DEAD인 Worker인가?
어떤 Worker ID를 후속 Job 소유권 기록에 사용할 수 있는가?
```

이 기능은 문서 처리 자체를 수행하지 않는다. 이후 Job Claim과 Lease 소유권을 안전하게 구현하기 위한
Worker 신원 및 생존 관리 계층을 제공한다.

## 2. 범위

### 2.1 포함 범위

- 설정으로 Worker 기능 활성화 여부 제어
- 애플리케이션 준비 완료 시 Worker 실행 인스턴스 등록
- Worker 역할명과 프로세스 실행 식별자 분리
- 실행 인스턴스마다 UUID 기반 `instance_id` 생성
- Worker ID, Instance ID, 호스트 이름, IP 주소 기록
- Worker 시작 시 `ACTIVE`, `started_at`, 최초 `last_heartbeat_at` 기록
- 설정된 주기마다 Heartbeat 갱신
- `ACTIVE`, `IDLE` 상태의 Worker만 Heartbeat 갱신
- 애플리케이션 정상 종료 시 `STOPPED`, `stopped_at` 기록
- Heartbeat와 종료 처리가 경쟁해도 `STOPPED`가 되돌아가지 않도록 조건부 UPDATE 적용
- 마지막 Heartbeat와 DEAD 기준 시간을 이용한 실질 상태 계산
- 관리자용 Worker 목록 조회 API 제공
- 기존 데이터가 있는 환경을 고려한 `instance_id` Migration
- Worker 설정, 생명주기, Repository, 관리자 API 검증

### 2.2 제외 범위

- PENDING Job 조회 및 Claim
- Lease Lock과 Claim Token 발급
- Job Attempt 생성
- Worker가 소유한 Job 실행
- 원본 파일 읽기와 텍스트 파싱
- Chunk 생성 및 저장
- 임베딩 모델 호출과 Vector 저장
- Worker의 자동 Job Polling
- 처리 중인 Job의 Lease 연장
- 만료된 Lease를 가진 Job 복구
- 실질 DEAD 상태를 DB의 저장 상태로 확정하는 감시 작업
- DEAD Worker가 보유한 Job의 자동 회수

제외된 기능은 Worker ID와 생존 상태를 기반으로 후속 처리 계층에서 구현한다.

## 3. 시스템 내 위치

문서 업로드부터 검색 가능한 Vector가 생성되기까지의 전체 흐름에서 Worker 등록과 Heartbeat는 Job
처리보다 먼저 준비돼야 한다.

```text
문서 업로드
  → file_objects 저장
  → documents 저장
  → document_versions 저장
  → embedding_jobs PENDING 생성

Worker 애플리케이션 시작
  → Worker 실행 인스턴스 등록
  → Heartbeat 갱신
  → Worker 생존 상태 확인 가능

후속 처리
  → 살아 있는 Worker가 PENDING Job Claim
  → 파싱
  → 청킹
  → 임베딩
  → INDEXED 완료
```

Worker 등록 정보는 `embedding_jobs.locked_by_worker_id`와
`embedding_job_attempts.worker_node_id`가 참조할 실행 주체가 된다.

```text
worker_nodes
  ├─ Worker 프로세스의 신원
  ├─ 현재 저장 상태
  ├─ 마지막 생존 확인 시각
  └─ 시작·종료 시각

embedding_jobs
  └─ 어떤 Worker가 Job을 소유하는지 기록

embedding_job_attempts
  └─ 어떤 Worker가 몇 번째 처리를 수행했는지 기록
```

## 4. 구성요소와 책임

### 4.1 `IndexingWorkerProperties`

`indexing.worker` 설정을 타입 안전하게 바인딩하고 시간 설정을 검증한다.

```text
enabled
→ Worker 기능 활성화 여부

name
→ Worker 역할명

heartbeatInterval
→ Heartbeat 실행 주기

deadThreshold
→ Heartbeat 만료 판정 기준
```

### 4.2 `WorkerSchedulingConfig`

Worker 기능이 활성화된 경우에만 Spring Scheduling을 활성화한다. API 전용 실행이나 일반 테스트에서
불필요한 Scheduler가 실행되지 않게 한다.

### 4.3 `WorkerLifecycleManager`

애플리케이션 시작과 종료 이벤트를 Worker 생명주기로 연결한다.

```text
ApplicationReadyEvent
→ Worker 등록

ContextClosedEvent
→ Worker 정상 종료 기록
```

현재 프로세스의 `workerId`와 `instanceId`를 메모리에 보관해 Heartbeat와 종료 처리에서 동일한 실행
인스턴스를 사용하게 한다.

### 4.4 `WorkerHeartbeatScheduler`

설정된 `heartbeat-interval`마다 현재 Worker의 Heartbeat 갱신을 요청한다. 등록된 Worker ID가 없으면
아무 작업도 수행하지 않는다.

### 4.5 `WorkerNodeCommandService`

Worker 등록, Heartbeat, 정상 종료를 담당하는 쓰기 서비스다. 모든 시각은 주입된 `Clock`을 기준으로
계산해 운영 코드와 테스트가 동일한 시간 정책을 사용하게 한다.

### 4.6 `WorkerNodeQueryService`

Worker 목록을 조회하고 현재 시각과 `dead-threshold`를 이용해 각 Worker의 실질 상태를 계산한다.
조회 과정은 DB 상태를 변경하지 않는다.

### 4.7 `WorkerNodeRepository`

Worker 목록 조회와 Heartbeat·종료 조건부 UPDATE를 담당한다. Heartbeat와 종료에서는 Entity 전체를
저장하지 않고 필요한 컬럼만 갱신한다.

### 4.8 `WorkerAdminController`

관리자에게 등록된 Worker와 Heartbeat 기준 실질 상태를 제공한다. Entity를 직접 노출하지 않고
`WorkerNodeResponse`로 변환한다.

## 5. Worker 식별 모델

### 5.1 역할명과 실행 식별자

`worker_name`과 `instance_id`는 서로 다른 질문에 답한다.

```text
worker_name
→ 이 프로세스는 어떤 역할을 수행하는가?

instance_id
→ 같은 역할의 여러 실행 중 정확히 어느 프로세스인가?
```

예를 들어 인덱싱 Worker를 세 개 실행하면 다음과 같이 저장된다.

| id | worker_name | instance_id | 의미 |
|---:|---|---|---|
| 1 | indexing-worker | UUID-A | 첫 번째 실행 프로세스 |
| 2 | indexing-worker | UUID-B | 두 번째 실행 프로세스 |
| 3 | indexing-worker | UUID-C | 세 번째 실행 프로세스 |

`worker_name`은 역할명이므로 중복을 허용한다. `instance_id`는 실행 프로세스를 구분해야 하므로
`UNIQUE`, `NOT NULL`로 관리한다.

### 5.2 주요 필드

| 필드 | 의미 | 제약 및 정책 |
|---|---|---|
| `id` | DB Worker 식별자 | PK, 후속 Job 소유권에서 참조 |
| `worker_name` | Worker 역할명 | NOT NULL, 중복 허용 |
| `instance_id` | 실행 프로세스 식별자 | UNIQUE, NOT NULL |
| `host_name` | 실행 호스트 이름 | 확인 실패 시 `unknown` |
| `ip_address` | 실행 호스트 IP | 확인 실패 시 null 가능 |
| `status` | 저장 상태 | ACTIVE, IDLE, DEAD, STOPPED |
| `last_heartbeat_at` | 마지막 생존 확인 시각 | 등록 시 최초 기록 |
| `started_at` | 실행 인스턴스 등록 시각 | NOT NULL |
| `stopped_at` | 정상 종료 시각 | 정상 종료 전까지 null |

### 5.3 재시작 처리

같은 호스트에서 같은 Worker 역할을 재시작해도 기존 행을 재사용하지 않는다. 새 프로세스는 새로운
UUID와 Worker 행을 가진다.

```text
이전 실행
workerId = 10
instanceId = UUID-A
status = STOPPED 또는 실질 DEAD

재시작
workerId = 11
instanceId = UUID-B
status = ACTIVE
```

이 방식은 실행 이력을 보존하고, 종료된 프로세스가 새 프로세스의 상태를 갱신하는 일을 방지한다.

## 6. 설정과 활성화 정책

```yaml
indexing:
  worker:
    enabled: ${INDEXING_WORKER_ENABLED:false}
    name: ${INDEXING_WORKER_NAME:indexing-worker}
    heartbeat-interval: ${INDEXING_WORKER_HEARTBEAT_INTERVAL:10s}
    dead-threshold: ${INDEXING_WORKER_DEAD_THRESHOLD:30s}
```

### 6.1 기본 비활성화

`enabled`의 기본값은 `false`다.

```text
API 전용 실행
→ Worker 행을 만들지 않음
→ Heartbeat Scheduler 실행 안 함

Worker 역할 실행
→ INDEXING_WORKER_ENABLED=true
→ 등록과 Heartbeat 활성화
```

애플리케이션 코드가 같아도 배포 역할에 따라 Worker 기능을 선택적으로 활성화할 수 있다.

### 6.2 시간 설정 검증

```text
heartbeatInterval > 0
deadThreshold > heartbeatInterval
```

기본 설정은 10초마다 생존을 기록하고 30초 동안 Heartbeat가 없으면 DEAD로 판단한다.

```text
Heartbeat 한 번 지연
→ 아직 DEAD 아님

여러 주기 동안 연속으로 Heartbeat 없음
→ DEAD 판정
```

## 7. 애플리케이션 시작과 Worker 등록

### 7.1 등록 시점

Spring Context가 생성됐다는 사실만으로는 애플리케이션이 정상적으로 요청과 백그라운드 작업을 처리할
준비가 끝났다고 보기 어렵다. 따라서 `ApplicationReadyEvent` 시점에 Worker를 등록한다.

```text
Spring Bean 초기화
→ Flyway와 JPA 초기화
→ 애플리케이션 준비 완료
→ ApplicationReadyEvent
→ Worker 등록
```

### 7.2 등록 흐름

```text
WorkerLifecycleManager 생성
  → 현재 프로세스용 UUID instanceId 준비

ApplicationReadyEvent 수신
  → 이미 등록된 workerId가 있는지 확인
  → 호스트 이름과 IP 주소 확인
  → 현재 시각 계산
  → worker_nodes INSERT
  → status = ACTIVE
  → started_at = 현재 시각
  → last_heartbeat_at = 현재 시각
  → 생성된 workerId를 메모리에 보관
```

호스트 정보 확인이 실패하면 등록 자체를 포기하지 않는다.

```text
host_name = unknown
ip_address = null
```

Worker ID와 Instance ID가 더 중요한 식별 정보이므로 제한된 호스트 정보로도 생명주기 관리를 계속한다.

### 7.3 중복 등록 방어

정상적인 Spring 실행에서는 `ApplicationReadyEvent`가 한 번 발생하지만, 중복 이벤트나 경쟁 상황에서도
같은 Manager가 여러 Worker를 유지하지 않도록 방어한다.

```text
workerId가 이미 존재
→ 추가 등록하지 않음

동시에 두 등록 결과가 생성
→ AtomicReference에 먼저 저장된 Worker 유지
→ 나중에 생성된 Worker는 즉시 STOPPED 처리
```

### 7.4 등록 실패

Worker 등록은 Job 소유권의 선행 조건이다. 등록 실패를 로그만 남기고 숨기면 Worker ID 없이 후속
처리가 실행될 수 있다.

```text
Worker 등록 실패
→ 예외 전파
→ 애플리케이션 시작 실패
```

## 8. Heartbeat 갱신

### 8.1 실행 주기

Heartbeat는 `fixedDelay`로 실행한다.

```text
Heartbeat 실행 완료
→ heartbeatInterval 대기
→ 다음 Heartbeat 실행
```

첫 Heartbeat도 애플리케이션 시작 직후가 아니라 설정된 주기만큼 기다린 뒤 실행한다. 등록 시점에
`last_heartbeat_at`을 함께 기록하므로 첫 Scheduler 실행 전에도 생존 시각이 존재한다.

### 8.2 조건부 UPDATE

Heartbeat는 다음 조건을 모두 만족하는 행만 갱신한다.

```text
id = 현재 프로세스가 등록한 workerId
instance_id = 현재 프로세스가 가진 instanceId
status IN (ACTIVE, IDLE)
```

변경하는 컬럼은 다음 두 개뿐이다.

```text
last_heartbeat_at = 현재 시각
updated_at = 현재 시각
```

개념적인 쿼리는 다음과 같다.

```sql
UPDATE worker_nodes
SET last_heartbeat_at = :heartbeatAt,
    updated_at = :heartbeatAt
WHERE id = :workerId
  AND instance_id = :instanceId
  AND status IN ('ACTIVE', 'IDLE');
```

### 8.3 갱신 결과

```text
updatedRows = 1
→ 현재 Worker의 Heartbeat 갱신 성공

updatedRows = 0
→ Worker 행이 없거나 Instance ID가 다르거나 갱신 불가능한 상태
→ 오류 로그 기록
```

Heartbeat 실패가 한 번 발생했다고 즉시 프로세스를 종료하지 않는다. 일시적인 DB 장애일 수 있으므로
다음 주기에 다시 시도하고, 외부에서는 `dead-threshold`를 기준으로 생존 여부를 판단한다.

## 9. Heartbeat와 종료의 동시성

### 9.1 Entity 전체 저장의 문제

Heartbeat와 종료가 같은 Worker 행을 각각 읽고 Entity Dirty Checking으로 저장하면 오래된 상태가
새 상태를 덮어쓸 수 있다.

```text
Heartbeat 트랜잭션
→ status = ACTIVE인 Entity 조회

종료 트랜잭션
→ status = STOPPED 저장
→ Commit

Heartbeat 트랜잭션
→ 오래된 ACTIVE Entity 저장

최종 결과
→ 종료된 Worker가 ACTIVE로 복구되는 오류
```

### 9.2 조건부 UPDATE로 해결

Heartbeat는 상태를 변경하지 않고 `ACTIVE`, `IDLE` 상태에서만 시간 컬럼을 갱신한다.

종료가 먼저 완료된 경우:

```text
종료 UPDATE
→ status = STOPPED

늦은 Heartbeat UPDATE
→ status 조건 불일치
→ updatedRows = 0
→ STOPPED 유지
```

Heartbeat가 먼저 완료된 경우:

```text
Heartbeat UPDATE
→ last_heartbeat_at 갱신

종료 UPDATE
→ status = STOPPED
→ stopped_at 기록

최종 상태
→ STOPPED
```

실행 순서와 관계없이 정상 종료 후 최종 상태가 `STOPPED`로 수렴한다.

## 10. 정상 종료

### 10.1 종료 흐름

```text
Spring Context 종료 시작
→ ContextClosedEvent
→ WorkerLifecycleManager.stopWorker()
→ 메모리의 workerId를 원자적으로 제거
→ workerId + instanceId 조건 확인
→ status = STOPPED
→ stopped_at = 현재 시각
→ updated_at = 현재 시각
```

`workerId`를 `getAndSet(null)`로 가져오기 때문에 종료 이벤트가 중복으로 전달돼도 동일한 Worker를
반복해서 종료하지 않는다.

### 10.2 종료 기록 실패

애플리케이션 종료 중 DB 연결이 이미 끊겼거나 저장소 장애가 발생할 수 있다. 종료 상태 기록 실패는
로그로 남기되 애플리케이션 종료를 방해하지 않는다.

```text
STOPPED 기록 실패
→ 오류 로그 기록
→ 프로세스 종료 계속
→ Heartbeat 중단
→ deadThreshold 경과
→ 관리자 조회에서 DEAD 판정
```

## 11. 비정상 종료와 DEAD 판정

### 11.1 비정상 종료

강제 종료, 컨테이너 장애, 서버 전원 장애에서는 `ContextClosedEvent`가 실행되지 않을 수 있다.

```text
DB 저장 상태 = ACTIVE
stopped_at = null
프로세스 = 실제로 종료됨
Heartbeat = 더 이상 갱신되지 않음
```

저장 상태만 보면 살아 있는 Worker처럼 보이므로 마지막 Heartbeat를 이용한 실질 상태가 필요하다.

### 11.2 기준 시각 계산

```text
heartbeatDeadline = now - deadThreshold
```

기본값이 30초라면 현재 시각보다 30초 이전의 Heartbeat는 만료로 본다.

### 11.3 실질 상태 규칙

```text
저장 상태가 STOPPED
→ STOPPED 유지

저장 상태가 DEAD
→ DEAD 유지

저장 상태가 ACTIVE 또는 IDLE
AND last_heartbeat_at이 null
→ DEAD

저장 상태가 ACTIVE 또는 IDLE
AND last_heartbeat_at <= heartbeatDeadline
→ DEAD

그 외
→ 저장 상태 유지
```

기준 시각과 정확히 같은 Heartbeat도 만료로 판단한다.

### 11.4 저장 상태와 실질 상태 분리

예를 들어 DB에는 다음과 같이 저장돼 있을 수 있다.

```text
status = ACTIVE
last_heartbeat_at = 15:00:00
```

현재 시각이 15:01:00이고 DEAD 기준이 30초라면 API 응답은 다음과 같다.

```text
status = DEAD
```

```text
Persisted Status
→ DB에 저장된 상태

Effective Status
→ 현재 시각과 Heartbeat를 반영해 조회 시 계산한 상태
```

조회에서 DEAD를 계산하더라도 DB의 `status`를 변경하지 않는다. 조회가 상태 변경을 수행하면 늦은
Heartbeat와 경쟁할 수 있고, 단순 관리자 조회가 Worker 복구 정책까지 책임지게 된다. DEAD 상태 확정과
Job 회수는 별도 감시·복구 흐름에서 처리한다.

## 12. Worker 상태 모델

| 상태 | 의미 | 현재 기능에서의 사용 |
|---|---|---|
| `ACTIVE` | 실행 중이며 정상적으로 Heartbeat를 보내는 상태 | 등록 시 저장 |
| `IDLE` | 실행 중이지만 처리할 Job을 기다리는 상태 | Heartbeat 허용, 실제 전환은 후속 처리 |
| `DEAD` | Heartbeat가 끊겼거나 사망이 확정된 상태 | 관리자 조회에서 실질 상태로 계산 |
| `STOPPED` | 정상 종료된 상태 | 종료 이벤트에서 저장 |

현재 생명주기의 대표 흐름은 다음과 같다.

```text
애플리케이션 시작
→ ACTIVE

ACTIVE 또는 IDLE
├─ 정상 종료 → STOPPED
└─ Heartbeat 만료 → Effective DEAD
```

`STOPPED`와 `DEAD`는 의미가 다르다.

```text
STOPPED
→ 정상적인 종료 경로가 실행됨

DEAD
→ 정상 종료 기록 없이 Heartbeat가 끊김
```

## 13. 관리자 API

### 13.1 요청

```http
GET /admin/workers
```

`SecurityConfig`에서 `/admin/**`는 ADMIN 역할로 보호된다.

### 13.2 처리 흐름

```text
ADMIN 요청
→ started_at DESC, id DESC로 Worker 목록 조회
→ heartbeatDeadline 계산
→ Worker별 Effective Status 계산
→ WorkerNodeResponse 변환
→ ApiResponse 반환
```

최근 시작한 Worker가 먼저 나오며 시작 시각이 같으면 ID가 큰 Worker가 먼저 나온다.

### 13.3 성공 응답

```json
{
  "success": true,
  "status": 200,
  "data": [
    {
      "workerId": 12,
      "workerName": "indexing-worker",
      "instanceId": "2f3a2d8c-1234-4abc-8def-123456789abc",
      "hostName": "docgrid-worker-01",
      "ipAddress": "10.0.0.12",
      "status": "ACTIVE",
      "lastHeartbeatAt": "2026-07-22T15:00:20",
      "startedAt": "2026-07-22T15:00:00",
      "stoppedAt": null
    }
  ]
}
```

응답의 `status`는 DB 저장 상태가 아니라 Heartbeat를 반영한 실질 상태다.

### 13.4 응답 필드

| 필드 | 설명 |
|---|---|
| `workerId` | DB Worker 식별자 |
| `workerName` | Worker 역할명 |
| `instanceId` | 프로세스 실행 식별자 |
| `hostName` | 실행 호스트 이름 |
| `ipAddress` | 실행 호스트 IP |
| `status` | Heartbeat를 반영한 실질 상태 |
| `lastHeartbeatAt` | 마지막 Heartbeat 성공 시각 |
| `startedAt` | Worker 등록 시각 |
| `stoppedAt` | 정상 종료 시각 |

### 13.5 에러 및 빈 목록

```text
ADMIN
→ 200 OK

일반 사용자
→ 403 Forbidden

미인증 사용자
→ 현재 SecurityConfig 동작에 따라 403 Forbidden

등록된 Worker 없음
→ 200 OK + 빈 배열
```

Worker가 한 건도 없는 것은 시스템 오류가 아니다. Worker 기능이 비활성화된 API 전용 실행일 수
있으므로 빈 배열을 정상 응답한다.

## 14. DB Migration

기존 `worker_nodes` 행이 있는 환경에서도 `instance_id` 제약을 안전하게 추가해야 한다.

```text
1. instance_id nullable 컬럼 추가
2. 기존 행을 legacy-{id}로 보정
3. NOT NULL 제약 적용
4. UNIQUE 제약 적용
5. Worker 상태 CHECK 제약 적용
```

```sql
ALTER TABLE worker_nodes
    ADD COLUMN instance_id VARCHAR(64);

UPDATE worker_nodes
SET instance_id = CONCAT('legacy-', id)
WHERE instance_id IS NULL;

ALTER TABLE worker_nodes
    ALTER COLUMN instance_id SET NOT NULL;

ALTER TABLE worker_nodes
    ADD CONSTRAINT uk_worker_nodes_instance_id UNIQUE (instance_id);

ALTER TABLE worker_nodes
    ADD CONSTRAINT ck_worker_nodes_status
    CHECK (status IN ('ACTIVE', 'IDLE', 'DEAD', 'STOPPED'));
```

기존 Worker ID가 7이면 `instance_id=legacy-7`로 보정된다. 새로 실행되는 Worker는 UUID를 사용한다.

## 15. 장애 및 경계 상황

### 15.1 Worker 프로세스 강제 종료

```text
ContextClosedEvent 없음
→ DB status는 ACTIVE로 남음
→ Heartbeat 중단
→ 기준 시간 경과
→ 관리자 응답 DEAD
```

### 15.2 DB 일시 장애로 Heartbeat 실패

```text
Heartbeat UPDATE 실패
→ 오류 로그
→ 다음 주기에 재시도
→ deadThreshold 안에 복구되면 ACTIVE 유지
→ 임계시간을 넘기면 DEAD로 조회
```

### 15.3 정상 종료 직후 늦은 Heartbeat

```text
status = STOPPED
→ Heartbeat 조건 ACTIVE/IDLE 불일치
→ updatedRows = 0
→ STOPPED 유지
```

### 15.4 같은 역할명의 Worker 다중 실행

```text
worker_name 동일
instance_id 서로 다름
→ 각각 독립된 Worker 행과 Heartbeat 관리
```

### 15.5 같은 호스트에서 Worker 재시작

```text
host_name과 ip_address 동일 가능
instance_id는 새 UUID
→ 이전 실행과 새 실행 구분
```

### 15.6 호스트 정보 확인 실패

```text
host_name = unknown
ip_address = null
→ Worker ID와 Instance ID로 등록 계속
```

## 16. 후속 처리와의 계약

Worker 생존 관리 계층은 후속 Job 처리에 다음 정보를 제공한다.

```text
workerId
→ embedding_jobs.locked_by_worker_id
→ embedding_job_attempts.worker_node_id

instanceId
→ 현재 애플리케이션 프로세스 실행 단위 검증

effectiveStatus
→ Job을 맡길 수 있는 Worker인지 판단

lastHeartbeatAt
→ Worker 장애 및 복구 판단 기준
```

Job 소유권을 부여할 때는 다음과 같은 검증이 가능해진다.

```text
Worker 행 존재
AND 저장 상태 ACTIVE 또는 IDLE
AND last_heartbeat_at > now - deadThreshold
→ Job을 맡길 수 있는 Worker
```

Worker의 `instance_id`와 Job의 Claim Token은 역할이 다르다.

```text
instance_id
→ Worker 프로세스 실행 기간 동안 동일

claim_token
→ 특정 Job을 Claim할 때마다 새로 발급
```

## 17. 테스트 설계

### 17.1 Entity 테스트

- 기본 ACTIVE Worker 생성
- ACTIVE Worker Heartbeat 갱신
- STOPPED Worker가 Entity 메서드로 Heartbeat 갱신되지 않음
- 정상 종료 시 STOPPED와 `stopped_at` 기록
- 최근 Heartbeat는 ACTIVE 유지
- 만료 Heartbeat는 DEAD 계산
- 저장 상태 STOPPED와 DEAD는 실질 상태 계산에서도 유지

### 17.2 Repository 테스트

- 같은 `worker_name`과 서로 다른 `instance_id` 저장
- 중복 `instance_id` 저장 시 DB Unique 제약 위반
- ACTIVE Worker 조건부 Heartbeat 성공 및 갱신 행 수 1
- STOPPED Worker의 늦은 Heartbeat 갱신 행 수 0
- 종료 시 Instance ID가 일치하는 Worker만 STOPPED 처리
- 최근 시작 시각과 ID 기준 목록 정렬
- 실제 OpenSQL에서 Migration과 조건부 UPDATE 검증

### 17.3 Service 테스트

- Worker 등록 시 현재 시각을 `started_at`, `last_heartbeat_at`에 동일하게 기록
- Heartbeat 결과가 1행이면 성공 반환
- Heartbeat 결과가 0행이면 실패 반환
- 정상 종료 결과가 1행이면 성공 반환
- Worker 목록 조회에서 단일 기준 시각으로 모든 실질 상태 계산

### 17.4 생명주기 테스트

- ApplicationReadyEvent에서 Worker 한 번 등록
- 중복 등록 요청 방지
- 등록된 Worker ID와 Instance ID 유지
- ContextClosedEvent에서 STOPPED 처리
- 종료 처리 중 예외가 발생해도 밖으로 전파하지 않음

### 17.5 Scheduler 테스트

- 등록된 Worker ID가 있으면 Heartbeat 실행
- 등록 전에는 Heartbeat 서비스 호출 안 함
- 조건부 UPDATE 실패 시 실패 결과 처리

### 17.6 Controller 테스트

- ADMIN Worker 목록 조회 성공
- 응답에서 실질 상태와 Instance ID 확인
- Worker가 없으면 빈 배열 반환
- 일반 사용자 403
- 미인증 사용자 403

## 18. 완료 기준

- Worker 기능이 활성화된 애플리케이션은 준비 완료 시 ACTIVE Worker 행을 생성한다.
- Worker 기능이 비활성화된 애플리케이션은 Worker를 등록하거나 Scheduler를 실행하지 않는다.
- 같은 `worker_name`을 가진 여러 Worker를 서로 다른 `instance_id`로 구분한다.
- 기존 Worker 데이터가 있어도 Migration이 성공한다.
- Worker 등록 시 시작 시각과 최초 Heartbeat 시각이 기록된다.
- ACTIVE와 IDLE Worker의 Heartbeat가 설정 주기로 갱신된다.
- STOPPED Worker는 늦은 Heartbeat로 다시 활성 상태가 되지 않는다.
- 정상 종료 시 STOPPED와 종료 시각이 기록된다.
- 비정상 종료 Worker는 Heartbeat 만료 후 관리자 조회에서 DEAD로 보인다.
- DEAD 판정을 위한 조회가 DB 상태를 변경하지 않는다.
- 관리자는 Worker 목록과 실행 인스턴스·호스트·Heartbeat·시작·종료 정보를 조회할 수 있다.
- 일반 사용자와 미인증 사용자는 관리자 API를 호출할 수 없다.
- 후속 Job 처리에서 사용할 Worker ID와 실행 인스턴스 식별 기반이 제공된다.
- 실제 OpenSQL Repository 테스트와 전체 빌드가 통과한다.
