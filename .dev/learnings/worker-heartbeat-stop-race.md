# Worker Heartbeat와 종료 상태 갱신 경쟁 조건

## 문제

Heartbeat와 애플리케이션 종료 처리가 같은 Worker 행을 동시에 읽고 엔티티 Dirty Checking으로 저장하면,
종료 트랜잭션이 STOPPED를 Commit한 뒤 오래된 Heartbeat 엔티티가 ACTIVE 상태를 다시 덮어쓸 수 있다.

## 적용 패턴

Heartbeat는 엔티티 전체를 저장하지 않고 다음 조건의 짧은 UPDATE를 사용한다.

```text
id = 현재 worker_id
instance_id = 현재 실행 instance_id
status IN (ACTIVE, IDLE)
```

UPDATE 대상도 `last_heartbeat_at`, `updated_at`으로 제한한다. 이 방식은 종료가 먼저 Commit되면
Heartbeat UPDATE 결과가 0건이 되어 STOPPED 상태를 되살리지 않는다.

정상 종료는 다음 조건으로 제한한다.

```text
id = 현재 worker_id
instance_id = 현재 실행 instance_id
status IN (ACTIVE, IDLE)
```

`status <> STOPPED`처럼 종착 상태 하나만 제외하면 이미 `DEAD`인 Worker를 정상 종료된 `STOPPED`로
덮어쓸 수 있다. 상태 전이는 출발 가능한 상태를 명시해 `ACTIVE/IDLE → STOPPED/DEAD`만 허용하고,
`DEAD`와 `STOPPED`는 서로 덮어쓰지 않는다.

## 검증

실제 OpenSQL Repository 테스트에서 STOPPED 갱신 후 늦은 Heartbeat를 실행하고 다음을 확인한다.

- Heartbeat UPDATE 결과 0건
- 상태 STOPPED 유지
- `stopped_at` 유지
- 마지막 Heartbeat 시각 미변경
- DEAD Worker 종료 갱신 결과 0건과 DEAD 유지
- STOPPED Worker 반복 종료 갱신 결과 0건과 최초 `stopped_at` 유지
