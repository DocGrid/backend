# JPA Bulk Update Context 초기화와 Outbox 완료 전이

## 문제

`@Modifying(clearAutomatically = true)`는 Bulk Update 대상 Entity만 분리하지 않고 현재
영속성 Context 전체를 초기화한다. 같은 Transaction에서 미리 잠금 조회한 Outbox Event도 detached
상태가 되므로, 이후 Entity 메서드로 상태를 변경해도 Dirty Checking 대상이 아니다.

이 상태에서 별도로 다시 조회한 전달 Attempt만 `SUCCEEDED`로 저장하면 다음 불일치가 남을 수 있다.

```text
SyncOutboxEvent = PROCESSING
SyncEventDeliveryAttempt = SUCCEEDED
```

Lease Recovery는 이미 성공한 Attempt를 실패로 종결할 수 없어 같은 Event를 반복 복구하게 된다.

## 적용 패턴

Handler는 자신의 구현 세부사항으로 영속성 Context를 초기화할 수 있다고 가정한다. Dispatcher는
Handler 성공 후 다음 순서로 완료 경계를 확정한다.

1. Event ID로 Outbox Event를 다시 잠금 조회한다.
2. 완료 시각 기준으로 Claim Token과 Lease를 다시 검증한다.
3. 다시 조회한 managed Event와 전달 Attempt를 같은 Transaction에서 완료한다.

Bulk Update의 `clearAutomatically = true`를 제거하면 Cache Entity가 오래된 값을 유지할 수 있으므로,
Orchestrator가 자신의 완료 Aggregate를 다시 확보하는 방식이 경계를 더 명확하게 보존한다.

## 회귀 검증

실제 권한 캐시 `REVOKED` Event를 Dispatch해 `bulkInvalidateBySource()`가 Context를 초기화하도록 한 뒤
다음을 DB에서 확인한다.

- Event 상태 `PROCESSED`
- `processed_at` 기록
- Claim Token, Dispatcher, Lease 만료 시각 제거
- 동일 Claim Attempt 상태 `SUCCEEDED`와 완료 시각 기록
