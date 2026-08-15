# Issue #207 빈 인덱싱 Queue Worker Polling Backoff 설계

closes #207

## 1. 배경

Worker Poller는 설정된 1초 주기마다 Worker 상태와 다음 `PENDING` Job을 조회한다. 처리할 Job이 없는
로컬 환경에서도 같은 조회 SQL이 계속 실행되고, `spring.jpa.show-sql`까지 켜져 있어 콘솔에 반복 SQL이
쌓였다.

## 2. 성공 기준

- 로컬 프로필의 Hibernate SQL 출력은 기본적으로 끄고 환경 변수로만 다시 켤 수 있다.
- 빈 Queue가 이어지면 DB Claim 조회 간격을 2초, 4초, 8초, 최대 10초까지 늘린다.
- Job을 하나라도 Claim하면 다음 기본 1초 주기부터 Queue를 다시 확인한다.
- Claim 오류는 빈 Queue로 취급하지 않고 다음 기본 주기에 재시도한다.
- Backoff 대기 중에는 Worker와 Job 조회 SQL을 실행하지 않는다.
- Heartbeat 10초 주기와 Lease 복구 정책은 변경하지 않는다.

## 3. 처리 흐름

Spring Scheduler의 고정 실행 주기는 1초로 유지한다. `WorkerPollingBackoff`가 다음 DB Polling 허용 시각을
메모리에서 판단하므로 대기 중 Scheduler 실행은 즉시 종료되고 Repository나 Service를 호출하지 않는다.

```text
기본 Scheduler tick 1초
→ Queue 비어 있음: 다음 Claim 지연 2초
→ 다시 비어 있음: 4초
→ 다시 비어 있음: 8초
→ 다시 비어 있음: 10초 상한 유지
→ Job Claim 성공: Backoff 초기화
```

상태는 Worker 프로세스 메모리에만 둔다. 재시작하면 기본 주기로 초기화되며, 분산 Worker마다 자신의 빈
Queue 관찰 결과를 독립적으로 관리한다.

## 4. 설정

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `indexing.worker.polling-interval` | `1s` | 활성 Queue의 기본 확인 주기 |
| `indexing.worker.idle-max-polling-interval` | `10s` | 빈 Queue Backoff 상한 |
| `JPA_SHOW_SQL` | `false` | 로컬 Hibernate SQL 콘솔 출력 |

최대 Polling 주기는 양수인 기본 주기보다 짧을 수 없다. 테스트 프로필은 E2E 대기 시간을 제한하기 위해
최대 주기를 1초로 둔다.

## 5. 오류 및 경계 조건

- Worker가 아직 등록되지 않았으면 Backoff를 변경하지 않는다.
- Claim Service 예외는 진단 로그를 남기고 Backoff를 증가시키지 않는다.
- Executor 제출 거부와 종료 중단은 기존 Slot 반환 규칙을 유지한다.
- Heartbeat와 Lease 복구 Scheduler는 별도 주기로 계속 실행된다.
- 새 Job 도착 알림 방식은 없으므로 빈 Queue 상한 시점에는 Claim까지 최대 10초 지연될 수 있다.

## 6. 테스트

- 빈 Queue 지연이 2초, 4초, 8초, 10초로 증가하는지 검증
- 10초 상한을 초과하지 않는지 검증
- Claim 성공 시 Backoff 초기화 검증
- Backoff 만료 전 Scheduler가 Claim Service를 호출하지 않는지 검증
- Claim 오류 뒤 다음 기본 주기에 다시 호출하는지 검증
- 설정 기본값과 잘못된 최대 주기 검증
- 전체 Backend 테스트 실행

## 7. 제외 범위

- DB 기반 또는 Worker 간 공유 Backoff 상태
- 새 Job 생성 이벤트를 통한 Poller 즉시 깨우기
- Heartbeat, Lease 복구, Retry Backoff 정책 변경
