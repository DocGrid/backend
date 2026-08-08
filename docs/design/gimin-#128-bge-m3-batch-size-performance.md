# 실제 BGE-M3 Batch Size 성능 비교 설계

## 1. 배경

문서 임베딩 경로는 `BAAI/bge-m3`의 Batch API와 `1~64` 범위의 설정 가능한 Batch Size를
지원한다. 현재 기본값 `16`은 기능 검증을 위해 정한 값이며, 같은 입력과 같은 실행 환경에서 수집한
성능 근거는 없다.

이번 작업은 제품 동작을 새로 추가하는 것이 아니라 실제 `/embed/batch` 경계를 반복 측정해 다음
질문에 답한다.

1. Batch Size가 커질 때 HTTP 왕복 감소와 모델 내부 Batch 처리로 처리량이 얼마나 증가하는가?
2. 처리량 증가와 요청 단위 p95·p99 지연, Embedding Server RSS 사이에 어떤 Trade-off가 있는가?
3. 로컬 CPU 개발 환경에서 문서 Batch 기본값 `16`을 유지할 근거가 있는가?

## 2. 범위

### 2.1 포함

- 실제 `POST /embed/batch` HTTP 호출
- Batch Size `1, 4, 8, 16, 32, 64`
- 같은 Text 집합과 같은 총 처리량을 사용한 순차 비교
- Warm-up과 본 측정 분리
- 처리량, 요청 지연 p50·p95·p99·max, Text당 평균 지연, 실패율 수집
- Docker 실행 시 Embedding Server PID 1의 RSS 기준선·최대값 수집
- Model명, 응답 순서·개수, 1024차원과 유한값 검증
- Host·Container·Python·Torch·Transformers 환경 지문 수집
- JSON 원본 결과와 Markdown 요약 결과 생성
- 일반 테스트와 분리된 재현 명령 제공

### 2.2 제외

- Mock Model 성능 비교
- Worker·Parsing·Vector DB를 포함한 전체 Pipeline 처리량
- 동시 HTTP 요청 부하
- GPU·CUDA·양자화 최적화
- OpenSQL 성능 측정
- Query Embedding 단건 API 변경
- BGE-M3 모델 교체

## 3. 측정 계약

### 3.1 입력 공정성

- 모든 Batch Size는 같은 순서의 결정적 한국어 Text Corpus를 처리한다.
- 한 Profile이 처리하는 Text 수는 최대 Batch Size `64`의 배수여야 한다.
- 마지막 불완전 Batch가 결과를 왜곡하지 않도록 모든 요청은 Profile의 Batch Size와 같은 개수의
  Text를 포함한다.
- 기본값은 Profile당 `64`개 Text와 본 측정 `3`회다. 더 안정적인 수치가 필요하면 환경 변수로
  Text 수와 반복 횟수를 높인다.
- 본 측정 Round마다 Batch Size 실행 순서를 회전해 Model Cache와 Host 열 상태가 한 Profile에만
  유리하게 작용하는 편향을 줄인다.

### 3.2 Warm-up

- Health Check 성공 뒤 각 Batch Size를 한 번씩 호출한다.
- Warm-up Vector는 계약을 검증하지만 성능 통계에는 포함하지 않는다.
- Model Loading 시간은 제외한다. Cold Start는 별도 운영 지표이며 Batch Size 비교 대상이 아니다.

### 3.3 통계

각 Batch Size별로 다음 값을 기록한다.

| 지표 | 계산 |
|---|---|
| 총 Text 수 | 성공 요청이 반환한 Vector 개수 합계 |
| 처리량 | 총 Text 수 / 성공 요청 지연 합계 |
| 요청 p50·p95·p99 | 선형 보간 Percentile |
| 요청 최대 지연 | 성공 요청 중 최대값 |
| Text당 평균 지연 | 성공 요청 지연 합계 / 총 Text 수 |
| 실패율 | 실패 요청 수 / 전체 요청 수 |
| RSS 기준선·최대값 | Profile 시간 구간의 `/proc/1/status` `VmRSS` 표본 |

측정은 순차 요청으로 수행한다. 이번 결과는 Batch Size 자체의 효율을 비교하는 기준선이며 다중
Worker 병렬 부하는 후속 Worker 처리량 작업에서 다룬다.

## 4. 실행 경계

일반 `./gradlew test`는 실제 모델을 요구하지 않는다. 별도 Task가 실제 서버가 준비됐는지 확인하고
Benchmark Script를 실행한다.

```bash
docker compose up -d embedding-server
./gradlew bgeBatchPerformanceTest
```

기본 설정을 확장할 때만 다음 환경 변수를 사용한다.

```bash
BGE_BENCHMARK_TOTAL_TEXTS=256 \
BGE_BENCHMARK_ROUNDS=5 \
./gradlew bgeBatchPerformanceTest
```

원본 JSON은 `build/reports/bge-m3-batch/` 아래에 생성해 Git에 포함하지 않는다. 재현 환경, 요약 수치,
기본값 판단만 `docs/test-results/`에 기록한다.

## 5. 실패 정책

- Health Check 실패, Model명 불일치, 응답 순서·개수 불일치, 1024차원이 아닌 Vector, NaN·Infinity는
  측정 실패다.
- 실패가 발생해도 완료된 Profile의 JSON 결과는 남기되 Process는 실패 Exit Code로 종료한다.
- RSS 수집은 Docker 외부 서버에서도 Benchmark를 실행할 수 있도록 선택 기능으로 둔다. Container가
  없으면 성능 측정은 계속하고 RSS를 `null`로 기록한다.
- URL, Docker Container 이름 외의 접속 Secret은 입력·로그·결과에 기록하지 않는다.

## 6. 기본값 판단

측정 전에 특정 Batch Size를 정답으로 고정하지 않는다.

1. 실패율이 0이고 Vector 계약을 만족한 Profile만 비교한다.
2. 최고 처리량 Profile을 먼저 확인한다.
3. 현재 기본값 `16`이 최고 처리량의 95% 이상이면 변경하지 않는다.
4. 더 큰 Batch가 의미 있는 처리량 개선을 보이더라도 p95 지연과 RSS 증가를 함께 기록한다.
5. Apple Silicon Docker CPU 측정값은 로컬 개발 기준선으로만 사용하고 운영 SLO로 해석하지 않는다.

## 7. 검증

- Percentile·Corpus·응답 계약·요약 계산 Python 단위 테스트
- 실제 BGE-M3 Smoke 규모 Benchmark
- 실제 BGE-M3 확장 규모 Benchmark
- 기존 Python Embedding API 계약 테스트
- 전체 Java 회귀 테스트

## 8. 커밋 분할

1. `docs: #128 BGE-M3 Batch Size 성능 비교 설계 추가`
2. `test: #128 실제 BGE-M3 Batch Size Benchmark 추가`
3. `docs: #128 BGE-M3 Batch Size 측정 결과 기록`

## 9. 완료 조건

- Batch Size 6종을 같은 입력으로 한 명령에서 재현할 수 있다.
- 처리량·p50·p95·p99·Text당 지연·실패율·RSS가 구조화돼 기록된다.
- 실제 응답의 Model·개수·순서·1024차원·유한값을 측정마다 검증한다.
- 일반 테스트는 Docker나 실제 BGE-M3에 의존하지 않는다.
- 기본값 유지 또는 변경 판단이 측정 환경의 한계와 함께 문서화된다.

