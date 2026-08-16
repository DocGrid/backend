# Embedding Provider 컨테이너 운영 복구 및 최종 부하 검증 결과

- 이슈: [#221](https://github.com/DocGrid/docgrid/issues/221)
- 측정일: 2026-08-17
- 결과: 성공
- 설계: [gimin-#221-embedding-provider-operations-recovery.md](../design/gimin-%23221-embedding-provider-operations-recovery.md)

## 1. 결론

제공 PDF 3건과 실제 BGE-M3 CPU Provider로 운영 복구, 정상·과부하, v1→v2→v3를 다시 검증했다.

- 정상 부하 batch 4/8/16 × 동시성 1/2의 요청 216/216건과 문서 162/162건이 성공했다.
- 운영 기본 batch 4·동시성 2의 요청 p95/p99는 `13.05/13.15초`, Peak RSS는
  `2,480.25 MiB`, OOM은 0회였다.
- 모든 정상 Profile의 최악 p99는 `24.07초`로 30초 read-timeout 안에 들어왔다.
- 동시성 4 과부하 33건 중 12건은 성공하고 21건은 의도한 HTTP 429로 거절됐으며, 예상 밖
  오류와 OOM은 0건이었다. 429 p95/p99는 `6.79/7.01ms`였다.
- Container 내부 Process 종료 뒤 `unless-stopped`가 자동 재시작했고 readiness는 `3.02초`에
  복구됐다. OOMKilled는 false였고 CPU·Memory 제한도 보존됐다.
- v1→v2→v3 Job 성공률은 100%, 재시도는 0회, 최신 성공 v3 검색 전환은 성공했다.
- 초기 `3 GiB` 후보는 OOM 없이 통과했지만 Peak RSS 대비 여유가 12.3%에 불과해, 출하 기본
  상한은 실측 결과에 따라 `4 GiB`로 조정했다. CPU 상한은 검증한 `4.0`을 유지한다.

## 2. 측정 환경과 입력

| 항목 | 값 |
|---|---|
| Model | `BAAI/bge-m3`, Dense 1024차원 |
| Provider | Python 3.11, Torch CPU, Uvicorn 단일 Process |
| Host | Docker Desktop, 10 vCPU, 8.32 GiB Memory |
| 실측 Resource Limit | CPU 4.0, Memory 3 GiB 후보 |
| 출하 Resource Limit | CPU 4.0, Memory 4 GiB |
| Restart Policy | `unless-stopped` |
| Adaptive Batch | max items 4, max code points 4,000, max estimated tokens 900 |
| Admission | 실행 1, FIFO 대기 1, permit 대기 15초 |
| 문서 read timeout | 30초 |
| PDF | 3건, 125,838 bytes |
| Chunk | 8 + 6 + 6 = 20 |
| 정상 반복 | Profile별 PDF 세트 3회 × 본 측정 3 Round, warm-up 1회 |
| RSS Sampling | Container PID 1, 250ms 간격 |

PDF 원문과 Chunk Text가 포함된 Corpus와 Raw JSON은 Git에 추가하지 않았다. 실행 시 생성된 결과는
`backend/build/reports/real-pdf-embedding/` 아래에만 보존했다.

## 3. 운영 설정 개선 전·후

| 항목 | 개선 전 | 개선 후 |
|---|---|---|
| Restart policy | `no` | `unless-stopped` |
| Memory limit | 무제한 | 실측 후보 3 GiB → 출하 기본 4 GiB |
| CPU limit | 무제한 | 4.0 CPU |
| Model Load 실패 | Background Thread 실패 뒤 Process 생존 가능 | Startup 실패로 전파해 Process 종료 |
| Health | `/health` readiness 한 종류 | `/health/live`, `/health/ready`, 기존 `/health` 호환 |
| Metric | 없음 | 요청·지연·Queue·Model·Memory·Process Metric |
| Alert | 없음 | Down·NotReady·Restart·Memory·p99·Queue·429 7개 |
| Monitoring | 없음 | 선택형 Prometheus Compose Profile |

## 4. 같은 PDF 정량 비교

개선 전은 #216의 CPU·Memory 무제한 Provider, 개선 후는 CPU 4.0과 더 엄격한 3 GiB 후보 제한에서
같은 PDF, Chunk, batch 4, 동시성 1·2를 반복 측정한 결과다. 출하 Memory 4 GiB는 이 결과 뒤
안전 여유만 늘렸으며 CPU 상한은 같다.

| 지표 | 개선 전 | 개선 후 | 변화 |
|---|---:|---:|---:|
| Job 성공률 | 3/3 (100%) | 3/3 (100%) | 유지 |
| 총 재시도 | 0회 | 0회 | 유지 |
| batch 4·동시성 2 요청 p95 | 7.99s | 13.05s | 63.3% 증가 |
| batch 4·동시성 2 요청 p99 | 8.60s | 13.15s | 52.9% 증가 |
| batch 4·동시성 2 문서 p99 | 13.31s | 19.79s | 48.7% 증가 |
| batch 4·동시성 2 Peak RSS | 2,263.58 MiB | 2,480.25 MiB | 9.6% 증가 |
| 실제 PDF OOM | 0회 | 0회 | 유지 |
| Provider 처리량 | 1.19 chunks/s | 0.728 chunks/s | 38.8% 감소 |
| v1→v3 전체 처리시간 | 21.54s | 40.61s | 88.5% 증가 |
| E2E 처리량 | 0.928 chunks/s | 0.492 chunks/s | 46.9% 감소 |
| 검색 가능 버전 전환 | v1→v2→v3 성공 | v1→v2→v3 성공 | 유지 |

CPU 상한은 Host의 10 vCPU 전체를 한 Provider가 점유하지 않게 하지만 지연과 처리량 비용이 분명하다.
현재 기본 batch 4의 최악 p99 `13.15초`는 30초 timeout의 43.8%이며, 정상 Profile 전체 최악
p99 `24.07초`도 timeout 안이다. 이 로컬 결과는 안전 기본값의 근거이지 운영 SLO를 대체하지 않는다.

## 5. 정상 반복·동시 부하

| Batch | 동시성 | 요청 성공 | 문서 성공 | 처리량 | 요청 p95 | 요청 p99 | 문서 p99 | Peak RSS | OOM |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 1 | 54/54 | 27/27 | 0.729 chunks/s | 6.90s | 7.19s | 12.06s | 2,329.90 MiB | 0 |
| 4 | 2 | 54/54 | 27/27 | 0.728 chunks/s | 13.05s | 13.15s | 19.79s | 2,480.25 MiB | 0 |
| 8 | 1 | 27/27 | 27/27 | 0.603 chunks/s | 14.80s | 14.81s | 14.81s | 2,694.20 MiB | 0 |
| 8 | 2 | 27/27 | 27/27 | 0.609 chunks/s | 23.97s | 24.07s | 24.07s | 2,681.52 MiB | 0 |
| 16 | 1 | 27/27 | 27/27 | 0.610 chunks/s | 14.51s | 14.65s | 14.65s | 2,677.50 MiB | 0 |
| 16 | 2 | 27/27 | 27/27 | 0.610 chunks/s | 23.93s | 24.07s | 24.07s | 2,675.76 MiB | 0 |

최대 동시성에서 실패·OOM 없이 최고 처리량의 95% 이상인 가장 작은 값은 batch 4였다. batch 8과
16은 더 느리고 timeout 여유도 작아 기본값을 변경할 근거가 없었다. 측정기는 batch 4와 30초
read-timeout 유지를 추천했다.

초기 3 GiB 후보 대비 최대 Peak RSS `2,694.20 MiB`의 여유는 `377.80 MiB`, 12.3%였다. OOM은
없었지만 30% 여유 기준에는 부족하므로 `2,694.20 × 1.3 = 3,502.46 MiB`보다 큰 4 GiB로 기본
상한을 올렸다. 더 엄격한 3 GiB에서 전체 부하가 성공했으므로 4 GiB 변경은 검증된 실행보다
메모리 여유만 늘린다.

## 6. 과부하 빠른 실패

batch 4·동시성 4에서 PDF 세트 3회 × 본 측정 3 Round를 실행했다.

| 지표 | 결과 |
|---|---:|
| 전체 HTTP 요청 | 33건 |
| 성공 | 12건 |
| HTTP 429 | 21건 |
| 예상 밖 오류 | 0건 |
| 429 p50 / p95 / p99 / max | 3.81 / 6.79 / 7.01 / 7.06ms |
| 성공 요청 p95 / p99 | 12.31 / 12.47s |
| 완료 처리량 | 0.759 chunks/s |
| Peak RSS | 2,482.98 MiB |
| OOM | 0회 |
| Container 생존 | 성공 |
| Container 재시작 | 0회 추가 |

Bounded queue 밖의 21건은 millisecond 단위로 거절됐고 HTTP 500·timeout은 없었다. 부하 직후
Prometheus `EmbeddingProviderOverloadSpike`가 실제 pending 상태로 전환되는 것도 확인했다.

## 7. 자동 재시작과 관측성

| 검증 | 결과 |
|---|---|
| 실제 Restart policy | `unless-stopped` |
| 실제 CPU / Memory 후보 제한 | 4.0 / 3,221,225,472 bytes |
| Process 장애 모사 | Container 내부 PID 1에 SIGTERM |
| RestartCount | 0→1→2 |
| readiness 중단→복구 | 3.02s |
| 복구 뒤 OOMKilled | false |
| 복구 뒤 Resource limit | 유지 |
| Prometheus Target | up, scrape 오류 없음 |
| `EmbeddingProviderRestarted` | 실제 firing 확인 |
| `EmbeddingProviderOverloadSpike` | 실제 pending 확인 |
| Prometheus Rule | 7개, 모두 health ok |

호스트의 `docker kill`은 Docker가 운영자 수동 중지로 취급해 `unless-stopped` 재시작을 억제했다.
따라서 자동 복구 시험은 Container 내부 Process 신호로 수행했다. 이 차이는 정책 실패가 아니라
Docker의 수동 중지 계약이며, 운영 Runbook에서도 두 동작을 구분해야 한다.

`/health`는 기존 `{"status":"ok"}` 계약을 유지했다. `/health/live`와 `/health/ready`는 uptime,
Model, active·waiting·max concurrency·max queue·saturated Snapshot을 반환했다. `/metrics`에서는
Process RSS, cgroup limit, Model 준비와 요청·Queue Histogram을 실제 확인했다.

## 8. v1→v2→v3 E2E

| 버전 | Chunk / Embedding | Job 처리시간 | 업로드→검색 확인 | Attempt | Retry | current·searchable |
|---:|---:|---:|---:|---:|---:|---|
| v1 | 8 / 8 | 13.48s | 15.70s | 1 | 0 | 성공 |
| v2 | 6 / 6 | 10.16s | 12.72s | 1 | 0 | 성공 |
| v3 | 6 / 6 | 9.74s | 12.19s | 1 | 0 | 성공 |

- 전체 처리시간: `40.61s`
- 전체 처리량: `0.492 chunks/s`
- Job 성공률: `3/3 (100%)`
- 재시도: `0회`
- 각 완료 뒤 과거 버전 ACTIVE Embedding: `0건`
- 최종 Vector Search 결과: current v3만 참조

첫 실행은 기존 PostgreSQL Volume의 초기 Role과 현재 Compose 환경값이 달라 Flyway 인증 단계에서
실패했다. Product Flow나 Embedding 요청 전의 Test Infrastructure 문제였으며 DB 설정을 바꾸지 않고
Volume의 기존 기본 Test Role로 재실행해 통과했다.

## 9. 자동 검증

| 검증 | 결과 |
|---|---|
| Java 전체 회귀 테스트 | 954 passed, 0 failed/skipped |
| Python 전체 테스트 | 67 passed |
| 실제 PDF v1→v2→v3 E2E | 1 passed |
| 실제 PDF 정상 부하 | 216/216 요청 성공, OOM 0 |
| 실제 PDF 과부하 | 12 성공, 21 의도한 429, 예상 밖 오류 0 |
| Compose 기본·monitoring Profile 렌더링 | 통과 |
| Prometheus Config | 통과 |
| Prometheus Alert Rule | 7개 통과 |
| Python 문법 / Git diff whitespace | 통과 |

## 10. 한계와 운영 판단

- 수치는 로컬 Apple Silicon CPU·Docker Desktop 환경의 결과이며 운영 SLO가 아니다.
- 4 CPU 상한은 Host 보호 대신 무제한 실행 대비 처리량을 낮춘다. 운영 처리량 목표가 더 높다면
  CPU를 추측으로 올리지 말고 같은 Profile로 6 CPU 등을 재측정해야 한다.
- Process RSS와 Docker cgroup 현재 사용량은 공유 Page 집계 방식 때문에 다르게 보일 수 있다.
  OOM 판정은 Docker `OOMKilled`, 선행 경보는 보수적인 Process RSS/Limit를 사용한다.
- 정확한 OOM 발생 횟수는 죽은 Process가 스스로 Metric으로 남길 수 없으므로 Docker Runtime과
  외부 Monitoring이 최종 근거다.
- Alertmanager 수신 채널은 운영 Secret과 조직 선택이 필요해 이번 범위에 포함하지 않았다.
