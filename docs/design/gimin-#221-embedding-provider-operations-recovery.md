# Embedding Provider 컨테이너 운영 복구 및 최종 부하 검증 설계

- 이슈: [#221](https://github.com/DocGrid/docgrid/issues/221)
- 담당: Gimini-3
- 상태: 구현 및 검증 완료

## 1. 배경

#213은 실제 PDF 기준 Batch와 timeout을 조정했고, #216은 실행 1건·FIFO 대기 1건의 bounded admission을
적용했으며, #219는 지수 backoff·jitter·Circuit Breaker를 추가했다. 현재 Provider는 정상 부하에서
OOM 없이 동작하지만 다음 운영 경계가 남아 있다.

- `docker-compose.yml`의 Embedding 컨테이너에는 restart policy와 CPU·Memory 제한이 없다.
- 모델 Load Thread가 실패하면 Uvicorn Process는 살아 있고 readiness만 영구적으로 503이 될 수 있다.
- `/health`는 모델 Load 여부만 반환해 실행·대기 Queue 포화 상태를 진단할 수 없다.
- 요청 결과·지연·Queue·RSS Metric과 이를 평가할 Alert Rule이 없다.
- 모든 보호 변경이 합쳐진 `develop` 기준 최종 정량 결과가 하나의 문서로 합산되지 않았다.

## 2. 성공 기준

1. Embedding 컨테이너는 `unless-stopped`로 실행되고 비정상 종료 뒤 자동 재시작한다.
2. Memory 기본 제한은 제한 적용 후 Peak RSS `2,694.20 MiB`에 30% 이상 여유를 둔 `4 GiB`이며
   환경 변수로 변경할 수 있다.
3. CPU 기본 제한 `4.0`은 Docker Desktop 10 vCPU 환경에서 실제 부하 p99가 문서 timeout 30초
   안에 들어오는지 검증한다.
4. 모델 Load 실패는 Process 시작 실패로 전파되어 restart policy가 복구 기회를 얻는다.
5. liveness, readiness, Prometheus Metric이 서로 다른 운영 질문에 답한다.
6. Alert Rule은 Down, Restart, 높은 RSS, 높은 p99, 지속 Queue 포화와 429 급증을 포함한다.
7. 반복·동시 부하에서 OOM이 0회이고 PDF v1→v2→v3 Job 성공률이 100%이며 최신 v3가 검색된다.

## 3. 범위와 경계

### 포함

- `docker-compose.yml`의 restart, CPU·Memory limit과 선택형 Prometheus Service
- Embedding Server의 동기 Model Startup, health 세분화와 Prometheus Metric
- Prometheus Scrape 설정과 Alert Rule
- Python 계약 테스트, Compose·Prometheus 설정 검증, 실제 Restart Recovery 검증
- 기존 반복·동시 실제 PDF Benchmark와 Version E2E 재실행

### 제외

- Alertmanager 수신 채널과 운영 Secret
- Kubernetes, 수평 확장과 분산 Circuit Breaker
- cAdvisor 같은 별도 Container Runtime Exporter

Embedding Process는 자신의 OOM Kill 원인을 죽은 뒤 보고할 수 없다. 따라서 Metric Alert는
`process_resident_memory_bytes / embedding_provider_memory_limit_bytes`의 90% 초과와 Process Restart를
선행·결과 신호로 사용한다. 정확한 OOM 횟수는 기존 Benchmark처럼 Docker Inspect의 `OOMKilled`를
최종 검증 결과에 기록한다.

## 4. Container Resource와 복구

기본 설정은 다음과 같다.

```yaml
restart: unless-stopped
mem_limit: ${EMBEDDING_CONTAINER_MEMORY_LIMIT:-4g}
cpus: ${EMBEDDING_CONTAINER_CPUS:-4.0}
```

초기 후보 `3 GiB`에서도 OOM은 없었지만 제한 적용 후 Peak RSS가 `2,694.20 MiB`로 상한의 87.7%에
도달했다. 실측 결과에 따라 기본값을 `4 GiB`로 올려 30% 이상의 여유를 확보했다. 운영 Host가
다르면 같은 PDF와 동시 부하 Benchmark를 다시 실행한 뒤 환경 변수로 조정한다. 제한을 단독으로
낮추지 않고 p95·p99, 처리량, Peak RSS와 OOM을 함께 판정한다.

Model은 ASGI Lifespan 시작 단계에서 동기적으로 Load한다. Load 성공 전에는 Uvicorn이 Ready 상태로
전환되지 않으며, 예외가 발생하면 Process가 종료되어 restart policy가 새 시작을 수행한다.

## 5. Health API

| Endpoint | 성공 조건 | 성공 응답 | 실패 |
|---|---|---|---|
| `GET /health/live` | Process가 HTTP 요청 처리 가능 | `status=alive`, uptime | 없음 |
| `GET /health/ready` | Model Load 완료 | Model과 admission Snapshot | HTTP 503 |
| `GET /health` | 기존 호환용 readiness | 기존 계약 `status=ok` | HTTP 503 |

Admission Snapshot은 `active_requests`, `waiting_requests`, `max_concurrency`, `max_queue_size`와
`saturated`를 반환한다. Queue가 순간적으로 가득 차도 모델은 이미 Load되어 있으므로 readiness는
200을 유지하고 Metric·Alert로 혼잡을 판단한다.

## 6. Metric 계약

`GET /metrics`는 Prometheus Text 형식을 반환한다.

| Metric | 의미 |
|---|---|
| `embedding_provider_requests_total{operation,outcome}` | 단건·Batch의 성공, 과부하, 준비 안 됨, 오류 수 |
| `embedding_provider_request_duration_seconds{operation,outcome}` | Queue 대기를 포함한 전체 요청 지연 |
| `embedding_provider_queue_wait_seconds{outcome}` | Permit 획득·Queue Full·대기 timeout 지연 |
| `embedding_provider_active_requests` | 현재 모델 실행 수 |
| `embedding_provider_waiting_requests` | 현재 FIFO 대기 수 |
| `embedding_provider_max_concurrency` | 설정된 실행 상한 |
| `embedding_provider_max_queue_size` | 설정된 대기 상한 |
| `embedding_provider_model_loaded` | Model 준비 여부(0/1) |
| `embedding_provider_memory_limit_bytes` | Cgroup Memory 상한, 무제한이면 0 |
| `process_resident_memory_bytes` | Process RSS |

Label은 `operation=single|batch`, `outcome=success|overloaded|unavailable|error`처럼 제한된 값만 사용해
문서 내용이나 예외 Message가 Metric에 들어가지 않게 한다.

## 7. Alert Rule

선택형 `monitoring` Compose Profile은 Prometheus를 실행하고 Embedding `/metrics`를 15초마다 Scrape한다.

| Alert | 조건 | 의미 |
|---|---|---|
| `EmbeddingProviderDown` | `up == 0` 1분 | Process·Network·health 장애 |
| `EmbeddingProviderRestarted` | Process 시작 시각 변경 | OOM 포함 비정상 재시작 조사 |
| `EmbeddingProviderHighMemory` | RSS/Limit > 90% 5분 | OOM 위험 |
| `EmbeddingProviderHighP99Latency` | p99 > 25초 5분 | Backend 30초 timeout 여유 소진 |
| `EmbeddingProviderQueueSaturated` | Waiting >= Queue 상한 1분 | 지속 혼잡 |
| `EmbeddingProviderOverloadSpike` | 429 비율 > 10% 5분 | 요청 부하가 admission 용량 초과 |

Alert Rule은 Prometheus `promtool check rules`로 문법을 검증한다. 실제 알림 수신 채널은 Secret과 운영
조직 선택이 필요하므로 범위에서 제외하고 Prometheus `/alerts`에서 상태를 확인한다.

## 8. 최종 검증

1. `docker compose config`로 restart·CPU·Memory와 monitoring Profile을 검증한다.
2. Python 계약 테스트로 health, Metric, 성공·429·500 계측과 permit 반환을 확인한다.
3. `promtool check config`와 `promtool check rules`를 실행한다.
4. Container 내부 PID 1에 SIGTERM을 보내 Process 장애를 모사하고 자동 재시작과 readiness 복구
   시간을 잰다. 호스트의 `docker kill`은 운영자 수동 중지로 분류되어 `unless-stopped`를 억제하므로
   자동 복구 시험에 사용하지 않는다.
5. Batch 4, 동시성 1·2와 과부하 동시성 4를 반복해 p50·p95·p99, 처리량, Peak RSS, OOM을 측정한다.
6. 제공된 PDF 3개를 v1→v2→v3으로 처리해 Job·Retry·최신 검색 Version을 확인한다.
7. 개선 전후와 최종 값을 `docs/test-results/gimin-#221-embedding-provider-operations-recovery.md`에 기록한다.

Closes #221
