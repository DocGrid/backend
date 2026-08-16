# Embedding Provider 재시도 폭주 및 장애 전파 방지 설계

- 이슈: [#219](https://github.com/DocGrid/docgrid/issues/219)
- 담당: Gimini-3
- 상태: 구현 및 검증 완료

## 1. 배경

Embedding Provider는 실행 1건·대기 1건으로 메모리를 보호하고 과부하를 HTTP 429로 거절한다.
Worker도 실패 Job을 `next_retry_at` 이후에 다시 Claim하는 지수 backoff를 이미 사용한다. 다만 현재는
다음 문제가 남아 있다.

- timeout, HTTP 429, 연결·5xx 장애와 영구적인 4xx 요청 오류가 충분히 분리되지 않는다.
- 같은 시각에 실패한 여러 Job의 지수 backoff가 같아 재시도가 다시 한꺼번에 시작될 수 있다.
- HTTP 429의 `Retry-After`가 Job 재실행 시각에 반영되지 않는다.
- Provider가 계속 실패해도 각 Worker Job이 실제 HTTP 요청을 반복한다.

## 2. 성공 기준

1. timeout, 과부하, Provider 장애, 요청 오류가 서로 다른 ErrorCode와 Retry 정책으로 분류된다.
2. retryable Job은 지수 backoff에 ±20% jitter를 적용하고 설정된 최대 지연을 넘지 않는다.
3. HTTP 429의 유효한 `Retry-After`는 Backoff 상한과 무관한 최소 재시도 지연으로 반영된다.
4. 연속 3회 Provider 장애 뒤 Circuit이 30초 동안 열리고 실제 HTTP 호출을 빠르게 차단한다.
5. Open 기간 뒤 동시에 도착한 요청 중 1건만 Half-open Probe를 실행한다.
6. HTTP 호출 내부에서는 재시도하지 않으며, 실패한 Job은 현재 Attempt 종료와 Transaction Commit 뒤에만
   `next_retry_at` 이후 새 Attempt로 실행된다.
7. Provider 복구 뒤 Circuit이 닫히고 재예약 Job이 성공할 수 있다.

## 3. 범위와 경계

### 포함

- `EmbeddingClient` HTTP 실패 분류와 `Retry-After` 해석
- Backend JVM별 공유 Circuit Breaker
- Worker 실패 정보에 최소 재시도 지연 전달
- Job-level 지수 backoff+jitter 계산
- 단위 테스트와 DB Claim 통합 테스트

### 제외

- HTTP 호출 메서드 내부의 즉시 재시도
- 여러 Backend JVM이 상태를 공유하는 분산 Circuit Breaker
- Provider 컨테이너 restart policy, Resource limit, 운영 Alert

Circuit 상태는 Backend JVM별이다. 여러 Backend 인스턴스가 하나의 Provider를 공유하는 환경에서는
각 인스턴스가 독립적으로 Probe할 수 있다. Redis 기반 분산 Circuit은 현재 단일 Backend 운영 범위를
넘으므로 후속 운영 복구 작업으로 남긴다.

## 4. 실패 분류

| 조건 | ErrorCode | Worker Failure | Retry |
|---|---|---|---|
| Read/connect timeout, HTTP 408 | `EMBEDDING_PROVIDER_TIMEOUT` | `EMBEDDING_PROVIDER_TIMEOUT` | 가능 |
| HTTP 429 | `EMBEDDING_PROVIDER_OVERLOADED` | `EMBEDDING_PROVIDER_OVERLOADED` | 가능 |
| 연결 거절, HTTP 5xx | `EMBEDDING_SERVER_UNAVAILABLE` | `EMBEDDING_PROVIDER_UNAVAILABLE` | 가능 |
| Circuit open | `EMBEDDING_PROVIDER_CIRCUIT_OPEN` | `EMBEDDING_PROVIDER_CIRCUIT_OPEN` | 가능 |
| HTTP 4xx, 단 408·429 제외 | `EMBEDDING_REQUEST_REJECTED` | `EMBEDDING_REQUEST_INVALID` | 금지 |
| HTTP 200 응답 계약 위반 | 기존 Embedding 결과 오류 | `EMBEDDING_RESULT_INVALID` | 금지 |

외부 응답 본문과 원문은 로그·DB에 저장하지 않는다. 진단에는 제한된 ErrorCode와 예외 Class 이름만
사용한다.

## 5. 재시도 지연

기본 설정은 다음과 같다.

```yaml
indexing.worker.retry-initial-delay: 10s
indexing.worker.retry-max-delay: 5m
indexing.worker.retry-jitter-ratio: 0.2
```

계산 순서는 다음과 같다.

1. `base = min(initial × 2^현재 retryCount, max)`
2. `jittered = min(base × [0.8, 1.2], max)`
3. `Retry-After`가 있으면 `max(jittered, Retry-After)`
4. 실패가 Circuit을 열었거나 Open 전 시작한 요청이 늦게 실패하면 남은 Open 시간을 함께 적용

`Retry-After`는 delta-seconds 형식만 수용한다. 음수·비정상 값은 무시한다. `retry-max-delay`는
애플리케이션이 계산한 지수 Backoff와 Jitter에만 적용하며, Provider 최소 지연은 허용 시각 전에
재호출하지 않도록 그대로 보존한다.

## 6. Circuit Breaker

기본 설정은 다음과 같다.

```yaml
embedding.provider.circuit-breaker.enabled: true
embedding.provider.circuit-breaker.failure-threshold: 3
embedding.provider.circuit-breaker.open-duration: 30s
```

상태 전이는 다음과 같다.

- `CLOSED`: 요청을 허용한다. retryable Provider 실패가 연속 3회면 `OPEN`으로 전환한다.
- `OPEN`: HTTP 호출 없이 `EMBEDDING_PROVIDER_CIRCUIT_OPEN`으로 빠르게 실패한다.
- `HALF_OPEN`: 30초 뒤 첫 요청 1건만 Probe로 허용한다. 나머지는 빠르게 실패한다.
- Probe 성공: 실패 횟수를 초기화하고 `CLOSED`로 전환한다.
- Probe 실패: 30초 Open 기간을 새로 시작한다.
- Probe 결과 기록 전 예상 밖 예외: 소유권을 반환해 다음 요청이 새 Probe를 실행할 수 있게 한다.

HTTP 4xx와 응답 계약 오류는 Provider 가용성 실패가 아니므로 Circuit 실패 횟수에 포함하지 않는다.
HTTP 429, timeout, 연결 장애와 5xx는 실제 부하·가용성 신호이므로 포함한다.

## 7. 재시도 중첩 방지

정확성 경계는 기존 DB Job 상태와 행 잠금이다.

1. 현재 Worker HTTP 호출이 반환되거나 timeout으로 종료된다.
2. 현재 Attempt를 `FAILED`로 끝내는 Transaction이 Job을 `PENDING`으로 바꾸고 `next_retry_at`을 저장한다.
3. Claim Query는 `next_retry_at <= now`인 Job만 `FOR UPDATE SKIP LOCKED`로 선택한다.
4. 동일 Job은 새 Claim과 Attempt가 만들어진 뒤에만 다시 실행된다.

따라서 애플리케이션은 HTTP 요청 안에서 재시도하지 않는다. timeout 뒤 Provider가 아직 계산 중이어도
최소 10초 이상의 Job-level 지연과 Circuit 상태가 추가 호출을 억제한다.

## 8. 검증 계획

- Circuit 연속 실패, Open fast-fail, 단일 Half-open Probe, 성공 복구
- timeout/429/5xx/4xx 분류와 `Retry-After` 추출
- 지수 backoff+jitter 범위, 최대 지연, `Retry-After` 우선
- 동일 실패 요청 멱등 재생 시 Retry 횟수와 실행 시각 불변
- `next_retry_at` 이전 Claim 제외 및 이후 단 한 Worker만 Claim
- 전체 Java 회귀 테스트

Closes #219
