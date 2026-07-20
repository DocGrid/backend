# Issue #39 Worker 등록 및 Heartbeat 설계

## 1. 목적

인덱싱 Worker가 PENDING Job을 Claim하기 전에 실행 중인 Worker 인스턴스를 식별하고 생존 상태를
확인할 수 있어야 한다. 이번 이슈는 Worker를 등록하고 Heartbeat를 기록하며 관리자에게 실질 상태를
제공한다.

로드맵상의 PR 4를 구현하는 작업이며 GitHub Pull Request 번호 4를 의미하지 않는다.

## 2. 범위

포함 범위는 다음과 같다.

- 애플리케이션 시작 시 Worker 등록
- 주기적인 Heartbeat 갱신
- 정상 종료 시 STOPPED 처리
- Heartbeat 만료 Worker의 DEAD 판정
- 관리자 Worker 목록 조회

PENDING Job Claim, Lease Lock, Claim Token, Job Attempt, 파싱, 청킹, 임베딩은 후속 이슈에서
구현한다.

## 3. Worker 식별자

`worker_name`은 `indexing-worker`와 같은 역할명이므로 여러 실행 인스턴스가 공유할 수 있다.
프로세스 실행 단위는 시작할 때 생성하는 UUID `instance_id`로 구분한다.

| 필드 | 의미 | 제약 |
|---|---|---|
| `worker_name` | Worker 역할명 | 중복 허용 |
| `instance_id` | 프로세스 실행 식별자 | UNIQUE, NOT NULL |
| `host_name` | 실행 호스트 이름 | 중복 허용 |
| `ip_address` | 실행 호스트 IP | 중복 허용 |

기존 데이터가 있는 환경에서도 Migration이 성공하도록 기존 행의 `instance_id`를 `legacy-{id}`로
보정한 뒤 NOT NULL과 UNIQUE 제약을 적용한다.

## 4. 실행 생명주기

```text
ApplicationReadyEvent
  → UUID instance_id 생성
  → worker_nodes INSERT
  → ACTIVE / started_at / last_heartbeat_at 기록

Heartbeat Scheduler
  → 현재 worker_id + instance_id 확인
  → ACTIVE 또는 IDLE일 때만 last_heartbeat_at 갱신

ContextClosedEvent
  → STOPPED / stopped_at 기록
```

Heartbeat와 종료 처리가 경쟁할 때 오래된 엔티티 전체를 저장하면 STOPPED 상태를 ACTIVE로 되돌릴
수 있다. 이를 방지하기 위해 Heartbeat는 ACTIVE와 IDLE 행의 시간 컬럼만 갱신하는 조건부 UPDATE를
사용한다.

Worker 등록 실패는 Job 처리의 안전한 선행 조건을 만족하지 못한 것이므로 애플리케이션 시작 실패로
전파한다. 종료 상태 기록 실패는 로그를 남기며, 비정상 종료와 동일하게 Heartbeat 만료로 판정할 수
있다.

## 5. 설정

```yaml
indexing:
  worker:
    enabled: ${INDEXING_WORKER_ENABLED:false}
    name: ${INDEXING_WORKER_NAME:indexing-worker}
    heartbeat-interval: ${INDEXING_WORKER_HEARTBEAT_INTERVAL:10s}
    dead-threshold: ${INDEXING_WORKER_DEAD_THRESHOLD:30s}
```

Worker 기능은 기본적으로 비활성화한다. API 전용 실행이나 테스트가 Worker 행을 만들지 않으며,
실제 Worker 역할의 배포에서 명시적으로 활성화한다. Heartbeat 주기는 0보다 커야 하고 DEAD 기준
시간보다 짧아야 한다.

## 6. DEAD 판정

이번 이슈에서는 별도 감시 작업이 DB 상태를 DEAD로 변경하지 않는다. 관리자 조회에서 다음 규칙으로
실질 상태를 계산한다.

```text
저장 상태가 STOPPED 또는 DEAD
→ 저장 상태 유지

ACTIVE 또는 IDLE이고 Heartbeat가 없거나
last_heartbeat_at <= now - dead_threshold
→ DEAD

그 외
→ 저장 상태 유지
```

조회 과정은 DB 상태를 변경하지 않는다. DEAD 확정과 해당 Worker가 보유한 Lock 복구는 후속 Lock
만료 복구 작업에서 처리한다.

## 7. 관리자 API

```http
GET /admin/workers
```

현재 프로젝트의 `SecurityConfig`에서 `/admin/**`는 ADMIN 권한으로 보호된다. 기존 보안 동작에 따라
인증되지 않았거나 ADMIN 권한이 없는 요청은 403으로 거부된다.

응답은 최근 시작한 Worker부터 정렬하며 Entity 대신 다음 정보를 담은 DTO를 반환한다.

- Worker ID와 역할명
- Instance ID
- 호스트 이름과 IP
- Heartbeat 기준 실질 상태
- 마지막 Heartbeat, 시작 및 종료 시각

## 8. 테스트

- 동일한 `worker_name`과 서로 다른 `instance_id` 저장
- 중복 `instance_id` DB 차단
- ACTIVE Worker Heartbeat 갱신
- STOPPED Worker의 늦은 Heartbeat 차단
- 최근 Heartbeat ACTIVE 및 만료 Heartbeat DEAD 판정
- 시작 이벤트의 단일 Worker 등록
- 종료 이벤트의 STOPPED 처리
- ADMIN 조회 성공, 일반 사용자와 미인증 사용자 403
- 실제 OpenSQL에서 Flyway Migration과 Repository 쿼리 검증

## 9. 완료 기준

- Worker 기능 활성화 시 ACTIVE 행이 생성된다.
- 동일 역할의 Worker 여러 개를 별도 실행 인스턴스로 관리한다.
- Heartbeat가 설정 주기로 갱신된다.
- 정상 종료 시 STOPPED가 기록된다.
- 만료된 Worker가 관리자 조회에서 DEAD로 보인다.
- 관리자만 Worker 목록을 조회할 수 있다.
- 후속 Job Claim에서 사용할 Worker ID와 Instance ID가 준비된다.
