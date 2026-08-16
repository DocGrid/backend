# 실제 PDF 기준 Embedding Batch·Timeout 안전 설정 설계

- 이슈: [#213](https://github.com/DocGrid/docgrid/issues/213)
- 작성일: 2026-08-16
- 상태: 구현 및 로컬 실측 완료

## 1. 배경

기존 문서 Embedding은 단건 검색과 같은 `5s` read timeout과 `batch-size=32`를
사용했다. 짧은 문장 Corpus로 선택한 기본값이었고, 운영 Parser가 만든 최대 1,000자
Chunk와 PDF 버전 업로드에서는 타임아웃과 재시도가 반복됐다.

문제는 timeout만 키우면 Provider의 느린 요청이 오래 점유될 수 있다는 점이다. 따라서 실제
PDF Chunk로 Batch 4·8·16의 지연·처리량·RSS·OOM을 먼저 측정하고, 그 결과로 Batch와
문서 전용 timeout을 함께 결정한다.

## 2. 목표와 완료 기준

- 제공된 PDF 3개를 운영 `PdfDocumentParser`와 `FixedSizeChunker`로 처리한다.
- Batch 4·8·16, Provider 동시성 1·2를 같은 Corpus와 반복 횟수로 비교한다.
- 요청·문서 전체 p50/p95/p99, 처리량, Peak RSS, 실패, OOM을 기록한다.
- 검색 단건의 5초 예산은 유지하고 문서 Batch에만 실측 timeout을 적용한다.
- 제공 PDF를 한 문서의 v1→v2→v3으로 처리했을 때 세 Job이 첫 시도에 성공한다.
- 각 완료 직후 current version이 전환되고 Vector Search가 해당 버전만 반환한다.
- 실행 결과는 `docs/test-results/`에 남기고 PDF 원문은 Git에 추가하지 않는다.

## 3. 설계

### 3.1 운영 Parser 기반 Corpus Exporter

`realPdfEmbeddingCorpus` Gradle Task는 `REAL_PDF_PATHS`를 받아 운영 Parser·Chunker를 그대로
실행한다. 임시 JSON에는 Provider에 보낼 Chunk Text와 재현을 위한 다음 정보를 넣는다.

- 파일 크기와 SHA-256
- Chunk 순서, 문자·UTF-8 Byte·예상 Token 수
- Chunk Content Hash
- 운영 `chunk-size=1000`, `overlap=200`

원문이 포함된 JSON은 Git에서 제외된 `backend/build/reports/` 아래에만 생성한다.

### 3.2 실제 PDF 안전성 Benchmark

Benchmark는 운영 Worker의 실행 방식을 재현한다.

1. 한 문서 내부의 Batch는 순차 호출한다.
2. 동시성은 여러 문서 Job 사이에만 적용한다.
3. 모델·Vector 차원·응답 개수·순서 계약을 모두 검증한 요청만 성공으로 집계한다.
4. Container PID 1의 RSS를 250ms 간격으로 수집하고 Profile 직후 `OOMKilled`·생존 상태를 읽는다.
5. 요청 실패, OOM, Provider 종료 중 하나가 나오면 더 큰 Profile을 실행하지 않는다.
6. Profile마다 원문을 제외한 JSON Checkpoint를 원자적으로 교체한다.

### 3.3 기본 Batch 선택 정책

최대 동시성 Profile에서 다음을 모두 만족하는 후보만 비교한다.

- HTTP 실패 0건
- OOM 0건
- Container 생존
- 가장 높은 처리량의 95% 이상

이 후보 중 가장 작은 Batch를 기본값으로 선택한다. 동률 처리량이면 메모리와 꼬리
지연 폭이 작은 설정을 우선하기 위함이다.

### 3.4 Timeout 선택 정책

선택 Batch의 모든 동시성 Profile 중 가장 높은 요청 p99에 2배 여유를 둔다. 결과는
5초 단위로 올림하고 최소 30초, 최대 60초 범위에서만 자동 추천한다. 60초를 넘으면
timeout을 늘리지 않고 `TIMEOUT_BUDGET_EXCEEDED`로 실패한다.

검색은 `embedding.server.read-timeout=5s`, 문서 Batch는
`embedding.document.read-timeout=30s`를 사용하도록 `RestClient`를 분리한다.

## 4. 결정된 기본값

| 설정 | 기존 | 변경 | 근거 |
|---|---:|---:|---|
| `embedding.document.batch-size` | 32 | **4** | 동시성 2에서 최고 처리량, 최소 p99·Peak RSS |
| `embedding.server.read-timeout` | 5s | **5s 유지** | 단건 검색의 짧은 응답 예산 보존 |
| `embedding.document.read-timeout` | 공유 5s | **30s** | batch 4 동시 부하 p99 8.12s의 2배 이상 |

## 5. 재현 명령

PDF 경로는 OS path separator로 연결한다.

```bash
REAL_PDF_PATHS='<v1.pdf>:<v2.pdf>:<v3.pdf>' ./gradlew realPdfEmbeddingCorpus
REAL_PDF_PATHS='<v1.pdf>:<v2.pdf>:<v3.pdf>' ./gradlew realPdfEmbeddingSafetyTest
REAL_PDF_PATHS='<v1.pdf>:<v2.pdf>:<v3.pdf>' ./gradlew realPdfVersionE2eTest
```

Raw Corpus·측정 JSON은 `backend/build/reports/real-pdf-embedding/`에 생성된다.

## 6. 범위 제한

다음은 별도 안정화 범위로 남겨둔다.

- 문자·Token 기준 adaptive batch
- Provider 전역 semaphore와 bounded queue
- 오류 유형별 backoff·jitter·circuit breaker
- Container restart policy·resource limit·OOM 경보

이 변경은 실측 기본값과 검색/문서 timeout 격리까지만 담당한다.
