# Embedding Provider 재시도 폭주 및 장애 전파 방지 검증 결과

- 이슈: [#219](https://github.com/DocGrid/docgrid/issues/219)
- 측정일: 2026-08-16
- 결과: 성공
- 설계: [gimin-#219-embedding-provider-retry-protection.md](../design/gimin-%23219-embedding-provider-retry-protection.md)

## 1. 결론

- timeout, HTTP 429, 연결·5xx 장애, 영구 4xx와 Circuit Open이 서로 다른 정책으로 분류됐다.
- 첫 Retry 10초에 ±20% Jitter를 적용하고 5분 상한을 지켰다.
- HTTP 429 `Retry-After: 15`는 DB `next_retry_at`의 최소 15초 지연으로 반영됐다.
- 세 번째 연속 실패가 Circuit을 열 때 30초 Open 시간이 Job 최소 지연으로 전달됐다.
- Circuit Open 중 실제 `RestClient.post()` 호출은 0건이었고, Open 종료 뒤 동시 요청 중 1건만
  Half-open Probe Permission을 얻었다.
- Open 전 이미 시작된 늦은 실패는 남은 Open 시간 23초를 반환해 중간 Attempt 소비를 방지했다.
- 전체 Java 950건과 실제 PDF v1→v2→v3 E2E가 통과했다.

## 2. 환경과 기본 설정

| 항목 | 값 |
|---|---|
| Backend | Spring Boot 3.5.16, Java 17 |
| Provider | 실제 BAAI/bge-m3 CPU Docker Container |
| 저장소 | PostgreSQL 17 + pgvector, MinIO |
| Retry 초기·최대 지연 | 10초 / 5분 |
| Retry Jitter | ±20% |
| Circuit 실패 임계치 | 연속 3회 |
| Circuit Open | 30초 |
| Provider Admission | 실행 1, FIFO 대기 1 |

Circuit과 실패 경로는 실제 Sleep과 외부 장애의 변동을 제거한 Clock·Mock HTTP 기반 결정적 테스트로
검증했다. 정상 경로 회귀는 실제 PDF, BGE-M3, PostgreSQL과 MinIO를 연결했다.

## 3. 실패 분류 결과

| 입력 | ErrorCode | Worker Failure | Retry |
|---|---|---|---|
| `HttpTimeoutException` | `SEARCH-004` | `EMBEDDING_PROVIDER_TIMEOUT` | 가능 |
| HTTP 429 | `SEARCH-003` | `EMBEDDING_PROVIDER_OVERLOADED` | 가능 |
| 연결 거절·HTTP 5xx | `SEARCH-001` | `EMBEDDING_PROVIDER_UNAVAILABLE` | 가능 |
| Circuit Open | `SEARCH-005` | `EMBEDDING_PROVIDER_CIRCUIT_OPEN` | 가능 |
| HTTP 4xx, 429 제외 | `SEARCH-006` | `EMBEDDING_REQUEST_INVALID` | 금지 |

외부 응답 본문, Endpoint, 문서 원문은 DB 실패 메시지와 결과 문서에 기록하지 않았다.

## 4. Backoff·Jitter·Retry-After

| 시나리오 | 결과 |
|---|---|
| Retry 0, Jitter 0% | 10초 |
| Retry 1, Jitter 0% | 20초 |
| Retry 2, Jitter 0% | 40초 |
| Retry 0, Jitter 20% | 반복 100회 모두 8~12초 범위 |
| 기본 Backoff 10초 + Retry-After 15초 | 15초 |
| 외부 최소 지연 10분 + 최대 지연 40초 | 40초로 제한 |
| 세 번째 실패로 Circuit Open | 30초 최소 지연 |
| Open 7초 뒤 기존 요청의 늦은 실패 | 남은 23초 최소 지연 |

DB 통합 테스트에서 `next_retry_at - failed_at = 15초`를 확인했다. 예약 시각 1μs 전에는 Claim
후보가 없고 정확한 예약 시각부터 해당 Job이 선택됐다. 같은 PENDING Job에 두 Worker가 동시에
접근하는 기존 `FOR UPDATE SKIP LOCKED` 테스트도 한 Worker만 소유권을 얻었다.

## 5. Circuit 상태 전이

| 시나리오 | 결과 |
|---|---|
| 연속 실패 2회 | Closed 유지 |
| 연속 실패 3회 | Open 전환, 30초 fast-fail |
| 실패 2회 뒤 성공 | 실패 횟수 초기화 |
| Open 중 호출 | HTTP 실행 전 `SEARCH-005` |
| Open 종료 뒤 동시 2건 | Probe 1건, fast-fail 1건 |
| Half-open Probe 성공 | Closed 복구 |
| Half-open Probe 실패 | 30초 Open 재시작 |
| Open 전 시작한 늦은 성공 | 새 Open 상태를 닫지 못함 |

HTTP 호출 메서드 안에는 Retry loop나 Sleep을 추가하지 않았다. 현재 Attempt가 실패 Transaction으로
종료된 후 DB `next_retry_at`을 만족해야만 새 Claim과 Attempt가 만들어진다. Provider의 실행 semaphore도
이전 요청이 아직 계산 중이면 새 모델 실행을 허용하지 않는다.

## 6. 실제 PDF v1→v2→v3 E2E

| 버전 | Chunk / Embedding | Job 처리시간 | 업로드→검색 확인 | Attempt | Retry | current·searchable |
|---:|---:|---:|---:|---:|---:|---|
| v1 | 8 / 8 | 9.75초 | 12.09초 | 1 | 0 | 성공 |
| v2 | 6 / 6 | 7.12초 | 8.86초 | 1 | 0 | 성공 |
| v3 | 6 / 6 | 7.44초 | 9.77초 | 1 | 0 | 성공 |

- 전체 처리시간: `30.72초`
- 처리량: `0.651 chunks/s`
- Job 성공률: `3/3 (100%)`
- 총 Retry: `0회`
- 최종 검색 가능 Version: `v3`

이 측정은 정상 Provider 회귀 결과다. 장애 경로에서 자연 발생한 Retry 수치가 아니라, 정상 입력에서
Circuit 추가가 Job·Version·검색 전환을 깨뜨리지 않는지 확인한 결과다.

## 7. 실행 결과

| 검증 | 결과 |
|---|---|
| `./gradlew compileJava` | 성공 |
| 핵심 Circuit·Retry 단위 테스트 | 성공 |
| Retry-After·Claim DB 통합 테스트 | 성공 |
| 전체 `./gradlew test` | 950 passed |
| 실제 PDF `realPdfVersionE2eTest` | 1 passed |
| `git diff --check` | 성공 |

실제 PDF 원문과 `build/reports/real-pdf-embedding/real-pdf-version-e2e.json`은 Git에 추가하지 않는다.

## 8. 남은 범위

- Circuit 상태는 Backend JVM별이며 여러 Backend가 공유하는 분산 Circuit은 아니다.
- Container restart policy, CPU·Memory limit, OOM·지연 Metric과 Alert는 운영 복구 작업에서 진행한다.
- 최종 운영 검증에서 반복·동시 부하와 전체 개선 전후 지표를 다시 합산한다.
