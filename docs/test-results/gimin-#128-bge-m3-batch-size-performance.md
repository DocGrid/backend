# 실제 BGE-M3 Batch Size 성능 비교 결과

## 1. 결과 요약

2026-08-08 실제 `BAAI/bge-m3` HTTP Server에서 Batch Size `1, 4, 8, 16, 32, 64`를 같은
한국어 Text 집합으로 비교했다. 본 측정에서는 각 Profile이 256개 Text를 3회 처리해 총 768개
Vector를 생성했다.

- 전체 본 측정: 1,140 HTTP 요청, 4,608 Vector
- 실패 요청: 0
- Vector 계약 오류: 0
- Batch 32 처리량: `17.08 texts/s`
- Batch 64 처리량: `17.38 texts/s`
- Batch 32는 최고 처리량의 `98.27%`
- Batch 32 p95: `1,975.70ms`
- Batch 64 p95: `3,719.82ms`
- Batch 64의 측정 최고 RSS는 Batch 32보다 약 `83.05MiB` 높음

처리량 차이가 1.76%에 불과한 반면 Batch 64의 p95는 Batch 32의 1.88배였다. 따라서 로컬 CPU
기준 문서 임베딩 기본값을 `16`에서 `32`로 변경한다.

설계와 통계 계약은 [실제 BGE-M3 Batch Size 성능 비교 설계](../design/gimin-%23128-bge-m3-batch-size-performance.md)에
기록했다.

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| Host | macOS 26.5.2, Apple Silicon arm64 |
| Host 논리 CPU | 10 |
| Embedding Container | `docgrid-embedding`, Linux |
| Python | 3.11.15 |
| Torch | 2.4.1, Thread 10 |
| Transformers | 4.44.2 |
| FlagEmbedding | 1.2.11 |
| CUDA | 사용하지 않음 |
| Model | `BAAI/bge-m3` |
| Vector 차원 | 1024 |
| Warm-up | Batch Size별 1회 |
| 본 측정 | 256 Text × 3 Round × 6 Profile |
| RSS 표본 간격 | 1초와 Profile 경계 |

이 결과는 Apple Silicon의 Docker CPU 실행 기준선이다. GPU 환경이나 공급사 운영 서버의 SLO로
사용하지 않는다.

## 3. 실행 명령

```bash
docker compose up -d embedding-server

BGE_BENCHMARK_TOTAL_TEXTS=256 \
BGE_BENCHMARK_ROUNDS=3 \
./gradlew bgeBatchPerformanceTest
```

실행 시간은 6분 47초였다. 원본 Request 표본과 환경 지문은 다음 Git 제외 경로에 생성했다.

```text
build/reports/bge-m3-batch/bge-m3-batch-latest.json
```

## 4. 측정 결과

| Batch | 요청 수 | 처리량 texts/s | p50 ms | p95 ms | p99 ms | 최대 ms | 평균 ms/Text | 최고 RSS MiB | 실패 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 768 | 5.85 | 166.03 | 222.97 | 254.01 | 362.37 | 170.81 | 2,176.80 | 0 |
| 4 | 192 | 11.12 | 356.99 | 409.23 | 442.71 | 459.94 | 89.93 | 2,176.80 | 0 |
| 8 | 96 | 13.57 | 585.59 | 637.61 | 681.90 | 683.95 | 73.69 | 2,176.68 | 0 |
| 16 | 48 | 15.15 | 1,027.70 | 1,224.44 | 1,498.55 | 1,543.40 | 66.02 | 2,185.02 | 0 |
| 32 | 24 | 17.08 | 1,857.51 | 1,975.70 | 1,985.38 | 1,988.18 | 58.56 | 2,176.68 | 0 |
| 64 | 12 | 17.38 | 3,681.53 | 3,719.82 | 3,726.36 | 3,728.00 | 57.55 | 2,259.72 | 0 |

모든 Profile은 기대 요청 수인 `768 / Batch Size`와 실제 요청 수가 일치했다. 각 Profile의 성공
Vector 수도 768개였고 응답 Model·Index·개수·1024차원·유한값 계약이 전 요청에서 유지됐다.

## 5. 해석

### 5.1 Batch 16에서 32

- 처리량 `15.15 → 17.08 texts/s`: `12.74%` 증가
- 평균 Text 지연 `66.02 → 58.56ms`: 감소
- p95 요청 지연 `1.22 → 1.98초`: Batch당 Text가 두 배이므로 증가
- 최고 RSS 차이는 표본 변동 범위였고 Batch 32가 더 높지 않았다.

문서 Worker는 한 Batch 응답을 받은 뒤 다음 요청을 보내는 순차 구조다. 따라서 전체 문서 처리량을
높이면서 단일 요청 지연을 2초 안팎으로 유지하는 Batch 32가 기존 16보다 낫다.

### 5.2 Batch 32에서 64

- 처리량 `17.08 → 17.38 texts/s`: `1.76%` 증가
- Batch 32는 최고 처리량의 `98.27%`
- p95 요청 지연 `1.98 → 3.72초`: `1.88배` 증가
- 최고 RSS `2,176.68 → 2,259.72MiB`: 약 `83.05MiB` 증가

Batch 64는 처리량 최고값이지만 추가 이득이 작고 꼬리 지연과 메모리 증가가 크다. 기본값으로
선택하지 않고 더 큰 CPU·GPU 환경에서 재측정할 수 있는 상한으로 유지한다.

## 6. 기본값 판정

| 기준 | Batch 32 결과 | 판정 |
|---|---:|---|
| 실패율 0 | 0% | 통과 |
| 최고 처리량의 95% 이상 | 98.27% | 통과 |
| Batch 64 대비 p95 절감 | 약 46.89% | 통과 |
| Batch 64 대비 최고 RSS 절감 | 약 83.05MiB | 통과 |

최종 판정은 다음과 같다.

```text
EMBEDDING_DOCUMENT_BATCH_SIZE 기본값: 16 → 32
허용 범위: 1~64 유지
Query Embedding 단건 API: 변경 없음
```

## 7. 자동 검증

### 7.1 Python 계약 테스트

```bash
python3 -m pytest -p no:cacheprovider \
  embedding-server/test_benchmark_batch_size.py \
  embedding-server/test_main.py
```

일회성 Embedding Container에서 `28 passed`를 확인했다.

검증 범위:

- 결정적 Corpus와 Batch Size 설정 검증
- Percentile 선형 보간
- Round별 Profile 순서 회전
- Model·Index·개수·차원·유한값 검증
- 처리량·p50·p95·p99·RSS 집계
- 현재 기본값 유지·변경 신호 계산
- 기존 `/embed`, `/embed/batch` 계약 회귀

### 7.2 실제 모델 Smoke

64개 Text·1회 Smoke에서도 Batch Size 6종, 95개 요청이 모두 성공했다. 이 결과는 실행기와 RSS
수집 경계 확인에만 사용하고 기본값 판단은 256개 Text·3회 확장 결과로 수행했다.

### 7.3 Java 전체 회귀

첫 실행은 현재 PostgreSQL Container가 Host `5432`에 노출된 것과 Test 기본 포트 `55432`가 달라
DB 기반 Test Context가 초기화되지 않았다. 저장소 설정을 변경하지 않고 Test Process에 실제 로컬
포트와 SSL Mode, 일회성 JWT를 주입해 다시 실행했다.

```bash
DB_PORT=5432 \
DB_SSLMODE=disable \
JWT_SECRET=<test-only-value> \
./gradlew test
```

결과:

```text
tests=709 failures=0 errors=0 skipped=0
```

## 8. 한계와 후속 작업

- CPU 단일 요청 Benchmark이므로 여러 Worker의 동시 요청 처리량은 포함하지 않는다.
- RSS는 1초 간격과 Profile 경계의 Container PID 1 표본이며 순간 Peak를 놓칠 수 있다.
- 입력은 결정적 한국어 문장 집합이지만 실제 운영 문서의 Token 길이 분포 전체를 대표하지 않는다.
- GPU 환경은 처리량·메모리 곡선이 다르므로 같은 명령으로 다시 측정해야 한다.
- Worker 수평 확장과 Queue Backpressure는 별도 성능 작업에서 검증한다.
