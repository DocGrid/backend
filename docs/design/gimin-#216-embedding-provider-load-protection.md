# Embedding Provider 부하·메모리 보호 설계

- 이슈: [#216](https://github.com/DocGrid/docgrid/issues/216)
- 작성일: 2026-08-16
- 상태: 구현 및 로컬 실측 완료

## 1. 배경

실제 PDF 기준 Batch 4와 문서 read timeout 30초를 적용한 뒤 단일 Job은 안정적으로
완료됐지만, BGE 모델 실행 경계에는 전역 동시성 제한이 없었다. 여러 Worker나 검색 요청이
동시에 들어오면 같은 모델 프로세스가 여러 `encode`를 함께 실행해 Peak RSS와 OOM 위험을
키우고, 처리할 수 없는 요청도 HTTP timeout까지 대기할 수 있었다.

또한 고정 Batch는 Chunk마다 다른 문자·Token 크기를 반영하지 못했다. 같은 4개 Batch라도
입력 길이에 따라 메모리와 지연이 달라지므로 요청 크기와 Provider 실행 수를 함께 제한한다.

## 2. 목표와 완료 기준

- Chunk 개수·Unicode Code Point·저장된 Token 수를 함께 사용하는 adaptive batch를 만든다.
- 단일 Provider 프로세스의 실제 모델 실행을 기본 1개로 제한한다.
- 실행 1건 뒤에 대기 1건만 허용하고 추가 요청은 HTTP 429로 빠르게 거절한다.
- 모델 예외와 대기 timeout 뒤에도 permit과 Queue 수가 복구된다.
- 과부하를 연결·응답 장애와 다른 오류로 보존한다.
- 실제 PDF 정상 부하에서 실패와 OOM이 없어야 한다.
- 과부하 부하에서는 429만 발생하고 Provider가 생존해야 한다.
- v1→v2→v3 Job 성공과 최신 검색 버전 전환에 회귀가 없어야 한다.

## 3. Adaptive Batch

`DocumentEmbeddingTransactionService`가 만드는 `ChunkSnapshot`에 DB의 `token_count`를
포함한다. `AdaptiveEmbeddingBatchPlanner`는 입력 순서를 바꾸지 않고 다음 상한을 동시에
적용한다.

| 설정 | 기본값 | 역할 |
|---|---:|---|
| `embedding.document.batch-size` | 4 | 요청당 최대 Chunk 수 |
| `embedding.document.max-code-points` | 4,000 | 언어·공백 형태와 무관한 문자량 상한 |
| `embedding.document.max-estimated-tokens` | 900 | Chunker가 저장한 Token 추정치 합계 상한 |

다음 Chunk를 추가했을 때 하나라도 상한을 넘으면 현재 Batch를 먼저 확정한다. Chunk는
Embedding과 검색의 불변 단위이므로 더 작게 자르지 않는다. 단일 Chunk가 예산을 넘는 경우
다른 Chunk와 결합하지 않은 단독 Batch로 격리한다. 현재 운영 Chunk 크기 상한은 1,000
Code Point이므로 기본 설정에서는 단일 Chunk가 문자 예산을 넘지 않는다.

Provider의 내부 `batch_size`에는 설정 상한이 아니라 실제 요청 Chunk 수를 전달한다. 마지막
Batch나 문자·Token 예산으로 작아진 Batch가 불필요하게 큰 내부 Batch로 실행되지 않게 한다.

## 4. Provider Admission Controller

Java Worker 내부 semaphore는 여러 애플리케이션 프로세스와 검색 요청을 함께 제한할 수 없다.
따라서 `/embed`와 `/embed/batch`가 실제 모델을 호출하기 직전에 Python
`ProviderAdmissionController`를 공통으로 통과한다.

기본 흐름은 다음과 같다.

1. 모델 실행 permit을 비차단 방식으로 먼저 획득한다.
2. 실행 중이면 Lock으로 보호된 bounded queue 자리 하나를 선점한다.
3. 대기 자리도 찼으면 즉시 `HTTP 429`를 반환한다.
4. 대기 요청은 최대 15초만 permit을 기다린다.
5. 모델 호출 성공·예외와 무관하게 `finally`에서 permit을 반환한다.

| 환경 변수 | 기본값 |
|---|---:|
| `EMBEDDING_PROVIDER_MAX_CONCURRENCY` | 1 |
| `EMBEDDING_PROVIDER_MAX_QUEUE_SIZE` | 1 |
| `EMBEDDING_PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS` | 15 |

Worker 기본 동시성 2는 `1 실행 + 1 대기`로 수용한다. 실제 PDF Batch 4의 단일 동시성 요청
p99가 약 5초였으므로 한 번의 순차 대기를 포함해도 문서 HTTP read timeout 30초 안에
완료할 수 있다. 세 번째 이상의 요청은 대기열에 쌓지 않는다.

## 5. 오류 계약

Provider Queue 거절과 permit 대기 timeout은 다음 계약으로 반환한다.

- HTTP Status: `429 Too Many Requests`
- Detail Code: `EMBEDDING_PROVIDER_OVERLOADED`
- `Retry-After`: permit 대기 제한을 초 단위로 올림한 값

Java `EmbeddingClient`는 429를 `SEARCH-003`인
`EMBEDDING_PROVIDER_OVERLOADED`로 변환한다. Worker는 이를 retry 가능한
`EMBEDDING_PROVIDER_OVERLOADED` 실패 유형으로 보고한다. 지수 backoff·jitter와
circuit breaker는 이 분류를 입력으로 사용하는 별도 범위다.

## 6. 실행 모델과 제약

현재 Dockerfile은 Uvicorn worker를 별도로 늘리지 않은 단일 프로세스 구성이다. 따라서
in-process semaphore가 컨테이너 전체 모델 실행 수를 제한한다. 향후 Uvicorn을 다중
프로세스로 실행하면 semaphore도 프로세스마다 생기므로 외부 공유 admission 계층이나
단일 모델 실행 전용 프로세스로 교체해야 한다.

## 7. 범위 제한

다음 항목은 이 변경에 포함하지 않는다.

- 오류 유형별 지수 backoff·jitter·circuit breaker
- Container restart policy·CPU/메모리 resource limit
- Prometheus OOM·지연 지표와 운영 경보

실행 결과는
[gimin-#216-embedding-provider-load-protection.md](../test-results/gimin-%23216-embedding-provider-load-protection.md)에 기록한다.
