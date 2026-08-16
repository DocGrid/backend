# Embedding Provider 부하·메모리 보호 검증 결과

- 이슈: [#216](https://github.com/DocGrid/docgrid/issues/216)
- 측정일: 2026-08-16
- 결과: 성공
- 설계: [gimin-#216-embedding-provider-load-protection.md](../design/gimin-%23216-embedding-provider-load-protection.md)

## 1. 결론

제공 PDF 3건의 운영 Chunk 20개로 정상 부하와 과부하를 분리해 반복 측정했다.

- 정상 동시성 1·2의 HTTP 요청 108/108건이 성공했고 OOM은 0건이었다.
- 동시성 2 요청 p99는 `8.60s`, 문서 p99는 `13.31s`, Peak RSS는 `2,263.58 MiB`였다.
- FIFO 대기를 보장하면서 기존 동일 Profile보다 요청 p99는 6.0% 늘었지만 문서 p99는 1.8%, Peak RSS는 9.9% 감소했다.
- 동시성 4의 총 33건 중 12건은 성공하고 21건은 의도한 HTTP 429로 거절됐으며 다른 오류는 0건이었다.
- 429 거절 p95/p99는 `6.14/6.52ms`였고 Provider는 OOM·재시작 없이 생존했다.
- v1→v2→v3 Job 성공률은 100%, 재시도는 0회, 최신 버전 검색 전환은 성공했다.

## 2. 측정 환경과 입력

| 항목 | 값 |
|---|---|
| Model | `BAAI/bge-m3`, Dense 1024차원 |
| Provider | Python 3.11, Torch CPU, Docker Desktop, Uvicorn 단일 프로세스 |
| Adaptive Batch | max items 4, max code points 4,000, max estimated tokens 900 |
| Admission | 실행 1, 대기 1, permit 대기 15초 |
| 문서 read timeout | 30초 |
| PDF | 3건, 125,838 bytes |
| Chunk | 8 + 6 + 6 = 20 |
| 반복 | Profile별 PDF 세트 3회 × 본 측정 3 Round, warm-up 1회 |
| RSS Sampling | Container PID 1, 250ms 간격 |

PDF 원문과 Chunk Text가 포함된 Raw Corpus·측정 JSON은 Git에 추가하지 않았다.

## 3. 동일 PDF 개선 전·후 비교

개선 전은 #213에서 같은 PDF, 운영 Parser·Chunker, batch 4, 동시성 2로 측정한 결과다.
개선 후는 adaptive batch와 Provider admission을 적용한 같은 Profile이다.

| 지표 | 개선 전 | 개선 후 | 변화 |
|---|---:|---:|---:|
| Job 성공률 | 3/3 (100%) | 3/3 (100%) | 유지 |
| 총 재시도 | 0회 | 0회 | 유지 |
| 요청 p95 | 7.71s | 7.99s | 3.6% 증가 |
| 요청 p99 | 8.12s | 8.60s | 6.0% 증가 |
| 문서 전체 p99 | 13.56s | 13.31s | 1.8% 감소 |
| 동시성 2 Peak RSS | 2,513 MiB | 2,263.58 MiB | 249.42 MiB, 9.9% 감소 |
| 실제 PDF OOM | 0회 | 0회 | 유지 |
| Provider 처리량 | 1.22 chunks/s | 1.19 chunks/s | 2.1% 감소 |
| v1→v3 전체 처리시간 | 21.68s | 21.54s | 0.6% 감소 |
| E2E 처리량 | 0.92 chunks/s | 0.928 chunks/s | 0.9% 증가 |
| 검색 가능 버전 전환 | v1→v2→v3 성공 | v1→v2→v3 성공 | 유지 |

실제 PDF Profile은 개선 전에도 OOM이 없었으므로 이 표로 OOM 감소율을 만들 수는 없다.
과거 별도 장문 batch 8에서 발생한 OOM 1회와도 입력이 달라 직접 비교하지 않는다. 이번
보호 효과는 Peak RSS 감소와 동시성 4 과부하 시 빠른 거절·Provider 생존으로 검증했다.
FIFO 적용으로 개별 요청 꼬리 지연과 처리량이 소폭 악화됐지만, 대기 요청이 새 요청에 계속
추월당하지 않는 상한 있는 지연으로 바뀌었고 30초 read timeout 예산은 충분히 유지됐다.

## 4. 정상 부하 결과

| Batch | 동시성 | 요청 성공 | 문서 성공 | 처리량 | 요청 p95 | 요청 p99 | 문서 p99 | Peak RSS | OOM |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 1 | 54/54 | 27/27 | 1.06 chunks/s | 4.60s | 5.17s | 8.79s | 2,184.25 MiB | 0 |
| 4 | 2 | 54/54 | 27/27 | 1.19 chunks/s | 7.99s | 8.60s | 13.31s | 2,263.58 MiB | 0 |

동시성 2에서도 Queue timeout과 HTTP 429가 없었다. 한 요청이 모델을 실행하는 동안 다른
요청 하나가 bounded queue에서 permit을 기다린 뒤 30초 문서 timeout 안에 완료됐다.

## 5. 과부하 빠른 실패 결과

동시성 4, 같은 PDF 세트 3회, 본 측정 3 Round를 수행했다. 측정기는 이 Profile에서만
HTTP 429를 예상된 보호 결과로 허용하고 HTTP 500·timeout은 즉시 중단하도록 실행했다.

| 지표 | 결과 |
|---|---:|
| 전체 HTTP 요청 | 33건 |
| 성공 | 12건 |
| HTTP 429 | 21건 |
| 예상 밖 오류 | 0건 |
| 429 p50 / p95 / p99 / max | 3.12 / 6.14 / 6.52 / 6.61ms |
| 성공 요청 p95 / p99 | 7.45 / 7.64s |
| 완료 처리량 | 1.23 chunks/s |
| Peak RSS | 2,252.33 MiB |
| OOM | 0회 |
| Container 생존 | 성공 |
| Container 재시작 | 0회 |

## 6. v1→v2→v3 E2E

| 버전 | Chunk / Embedding | Job 처리시간 | 업로드→검색 확인 | Attempt | Retry | current·searchable |
|---:|---:|---:|---:|---:|---:|---|
| v1 | 8 / 8 | 7.04s | 9.08s | 1 | 0 | 성공 |
| v2 | 6 / 6 | 4.66s | 6.07s | 1 | 0 | 성공 |
| v3 | 6 / 6 | 4.69s | 6.39s | 1 | 0 | 성공 |

- 전체 처리시간: `21.54s`
- 전체 처리량: `0.928 chunks/s`
- Job 성공률: `3/3 (100%)`
- 재시도: `0회`
- 각 완료 뒤 과거 버전 ACTIVE Embedding: `0건`
- 최종 Vector Search 결과: current v3만 참조

## 7. 자동 검증

| 검증 | 결과 |
|---|---|
| Java 전체 회귀 테스트 | 925 passed |
| Python 전체 테스트 | 58 passed |
| 실제 PDF v1→v2→v3 E2E | 1 passed |
| Adaptive Batch 개수·문자·Token·순서 계약 | 통과 |
| Provider 최대 모델 실행 동시성 1 | 통과 |
| Provider 대기 요청 FIFO 순서 | 통과 |
| Queue 초과 즉시 429와 permit 복구 | 통과 |
| 정상·과부하 Container OOM·생존 확인 | 통과 |

## 8. 한계

- 수치는 로컬 Apple Silicon CPU·Docker Desktop 환경의 결과이며 운영 SLO가 아니다.
- in-process semaphore는 현재 Uvicorn 단일 프로세스 구성에서만 전역 제한이다.
- 이번 변경은 과부하를 분류하지만 backoff·jitter·circuit breaker 정책은 아직 적용하지 않았다.
- Container resource limit·restart policy와 운영 OOM·지연 경보는 후속 범위다.
