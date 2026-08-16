# 실제 PDF Embedding Batch·Timeout 안전성 검증 결과

- 이슈: [#213](https://github.com/DocGrid/docgrid/issues/213)
- 측정일: 2026-08-16
- 결과: 성공
- 설계: [gimin-#213-real-pdf-embedding-safety.md](../design/gimin-%23213-real-pdf-embedding-safety.md)

## 1. 결론

제공 PDF 3건의 운영 Chunk 20개를 Batch 4·8·16과 동시성 1·2에서 반복 측정했다.
총 162개 문서 실행·216개 HTTP 요청이 성공했고 OOM은 0건이었다.

- `batch-size=4`가 동시성 2에서 처리량 `1.22 chunks/s`로 가장 빨랐다.
- batch 4 동시 부하 요청 p99는 `8.12s`, 문서 전체 p99는 `13.56s`였다.
- batch 8·16은 동시 부하 p99가 약 `19.9s`였고 처리량도 batch 4보다 낮았다.
- batch 4의 Peak RSS는 동시성 1에서 `2,316 MiB`, 동시성 2에서 `2,513 MiB`였다.
- 따라서 기본 Batch는 `4`, 문서 전용 read timeout은 `30s`로 결정했다.
- 제공 PDF를 v1→v2→v3으로 재처리한 결과 Job 성공률 100%, 재시도 0회,
  전체 처리시간 `21.68s`, 최신 버전 검색 전환 성공이었다.

## 2. 측정 환경과 Corpus

| 항목 | 값 |
|---|---|
| Model | `BAAI/bge-m3`, Dense 1024차원 |
| Provider | Python 3.11.15, Torch CPU, Docker Desktop |
| Host | macOS aarch64, Logical CPU 10 |
| Docker 할당 메모리 | 7.75 GiB |
| Chunk | `size=1000`, `overlap=200` |
| PDF | 3건, 125,838 bytes, SHA-256 기록 |
| Chunk 수 | 8 + 6 + 6 = 20 |
| Chunk 문자 수 | min 180, p50 652, p95 1,000, max 1,000 |
| 예상 Token 수 | min 27, p50 155.5, p95 206.85, max 223 |
| 본 측정 | Profile별 PDF 세트 3회 반복 × 3 Round |
| RSS Sampling | Container PID 1, 250ms 간격 |

문서 내부 Batch는 순차로 호출하고, 동시성 2는 두 문서 Job이 Provider를 함께 사용하는
운영 형태로 적용했다. PDF 원문과 Chunk Text는 커밋 결과에 포함하지 않았다.

## 3. Batch 4·8·16 실측

| Batch | 동시성 | 요청 성공 | 문서 성공 | 처리량 (chunks/s) | 요청 p50 | 요청 p95 | 요청 p99 | 문서 p99 | Peak RSS | OOM |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **4** | 1 | 54/54 | 27/27 | **1.05** | 3.63s | 4.67s | **4.90s** | **8.40s** | **2,316 MiB** | 0 |
| **4** | 2 | 54/54 | 27/27 | **1.22** | 5.81s | 7.71s | **8.12s** | **13.56s** | **2,513 MiB** | 0 |
| 8 | 1 | 27/27 | 27/27 | 0.91 | 6.42s | 9.68s | 9.89s | 9.89s | 2,621 MiB | 0 |
| 8 | 2 | 27/27 | 27/27 | 0.92 | 13.23s | 18.62s | 19.91s | 19.91s | 2,826 MiB | 0 |
| 16 | 1 | 27/27 | 27/27 | 0.80 | 7.41s | 11.31s | 11.73s | 11.73s | 2,572 MiB | 0 |
| 16 | 2 | 27/27 | 27/27 | 0.90 | 13.67s | 19.27s | 19.55s | 19.55s | 2,829 MiB | 0 |

이 PDF들은 문서당 6~8 Chunk이므로 batch 8과 16의 HTTP 호출 수는 같다. 그럼에도 Provider에
넘기는 내부 batch size가 커질수록 지연이 늘고 처리량이 낮아졌다. 현재 Corpus에서는 큰
Batch를 유지할 이유가 없다.

## 4. 개선 전·후 비교

개선 전은 2026-08-15 localhost QA에서 같은 PDF를 v1→v2→v3으로 접수한 Job 11·13의
Attempt 12건으로 측정했다. 기존 DB가 Test Schema 초기화 후 보존되지 않아, 실패 시
캡처한 Attempt 지연 기록을 기준으로 삼았다. 처리시간은 Backoff를 제외한 Attempt 합계이므로
실제 Wall Time의 하한이다.

| 지표 | 개선 전: batch 32 / 5s 공유 | 개선 후: batch 4 / 문서 30s | 판정 |
|---|---:|---:|---|
| Job 성공률 | 0/3 (0%) | 3/3 (100%) | 성공 |
| 재시도 횟수 | 총 9회, Attempt 12건 | 0회, Attempt 3건 | 100% 감소 |
| 요청/Attempt p50 | 5.28s | 5.81s (동시성 2) | timeout 중단이 완료 응답으로 변경 |
| 요청/Attempt p95 | 6.63s | 7.71s (동시성 2) | 성공 응답 수신 |
| 요청/Attempt p99 | 7.27s | 8.12s (동시성 2) | 30s 예산 안전 범위 |
| 문서 전체 처리시간 | v1 ≥23.27s, v2 ≥21.86s, v3 ≥21.47s 후 실패 | v1 9.11s, v2 6.33s, v3 6.25s | 성공하며 58~71% 단축 |
| 총 처리시간 | ≥66.60s 후 3건 실패 | 21.68s 후 3건 성공 | 하한 대비 67.4% 단축 |
| 완료 처리량 | 0 completed chunks/s | 0.92 completed chunks/s (E2E) | 검색 가능 출력 생성 |
| Peak RSS | 기존 실패 Job은 미수집; 별도 장문 batch 8에서 ≥2.17GiB 후 OOM | 2.45GiB (batch 4, 동시성 2) | 동일 PDF 반복에서 OOM 없음 |
| OOM 횟수 | 별도 장문 batch 8 stress 1회 | PDF 162회 처리 0회 | 본 Corpus 안전 |
| 검색 가능 버전 전환 | v1·v2·v3 모두 실패 | v1→v2→v3 모두 성공 | 최신 v3만 반환 |

개선 후 요청 p95/p99가 개선 전보다 숫자상 높은 것은 성능 악화가 아니다. 개선 전 표본은
5초 read timeout으로 종료된 실패 Attempt이고, 개선 후는 1024차원 Vector 검증까지 끝난 성공
응답이다. 완료율·재시도·총 처리시간을 함께 해석해야 한다.

## 5. v1→v2→v3 최종 E2E

| 버전 | Chunk / Embedding | Job 처리시간 | 업로드→검색 확인 | Attempt | Retry | current·searchable |
|---:|---:|---:|---:|---:|---:|---|
| v1 | 8 / 8 | 7.10s | 9.11s | 1 | 0 | 성공 |
| v2 | 6 / 6 | 4.79s | 6.33s | 1 | 0 | 성공 |
| v3 | 6 / 6 | 4.76s | 6.25s | 1 | 0 | 성공 |

- Job 처리 p50/p95/p99: `4.79s / 6.87s / 7.05s`
- 업로드→검색 확인 p50/p95/p99: `6.33s / 8.83s / 9.05s`
- 세 버전 전체: `21.68s`, 20 Chunk, `0.92 completed chunks/s`
- 각 전환에서 과거 버전의 `ACTIVE` Embedding은 0건이었다.
- 각 current Chunk로 Query Vector를 만들어 검색했을 때 모든 후보의 `document_version_id`가
  해당 current version과 일치했다.

## 6. 자동 검증

| 검증 | 결과 |
|---|---|
| Python 3.11 Benchmark 단위 테스트 | 31 passed |
| Java 전체 회귀 테스트 | 883 passed |
| 실제 PDF v1→v2→v3 E2E | 1 passed |
| Job 성공·Attempt·Retry 불변식 | 통과 |
| Chunk 수 = Embedding 수, Dense 1024차원 | 통과 |
| current 전환 후 과거 ACTIVE Embedding 0건 | 통과 |
| Vector Search가 current version만 반환 | 통과 |

## 7. 한계

- 로컬 Apple Silicon CPU·Docker Desktop 단일 환경의 절대 수치이며 운영 SLO가 아니다.
- 개선 전 Peak RSS는 같은 세 PDF Job에서 수집하지 못했다. 별도 장문 OOM 재현 수치와
  이번 PDF Profile을 직접적인 메모리 절감률로 비교하면 안 된다.
- Provider 동시성 2에서도 OOM은 없었지만, 전역 semaphore·bounded queue·resource limit은 아직
  적용하지 않았다.
- adaptive batch, retry backoff·jitter·circuit breaker, Container 자동 복구·경보는 후속
  안정화 범위에서 다시 부하 검증해야 한다.
