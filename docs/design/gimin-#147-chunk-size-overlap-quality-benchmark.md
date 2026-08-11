# Chunk Size·Overlap 검색 품질 및 비용 비교 Benchmark 설계

- 관련 이슈: [#147](https://github.com/DocGrid/backend/issues/147)
- 작성일: 2026-08-11
- 상태: 구현 예정

## 1. 배경

문서 인덱싱 파이프라인은 Unicode Code Point 기준 `FixedSizeChunker`를 사용하고 기본값으로
`chunkSize=1000`, `overlap=200`을 적용한다. 이 값은 기능적으로 검증됐지만 Chunk 경계에 걸친
근거가 검색에서 얼마나 보존되는지, Overlap 증가가 임베딩·저장량을 얼마나 늘리는지 같은 조건에서
비교한 실측 근거는 없다.

Chunk 수만 비교하면 검색 품질 손실을 발견할 수 없고, 품질만 비교하면 중복 비용을 판단할 수 없다.
이번 작업은 실제 Chunker와 BAAI/bge-m3를 사용해 품질과 비용을 함께 측정한다.

## 2. 목표와 성공 기준

- 8개 Chunk Size·Overlap Profile을 같은 Corpus에서 비교한다.
- Chunk 경계 주변에 정답 근거를 배치해 Overlap의 Answer Coverage 효과를 검증한다.
- 실제 BAAI/bge-m3 Vector로 Exact Cosine 검색 품질을 측정한다.
- Hit@1·Hit@3·MRR@10과 Chunk·중복량·임베딩 시간을 함께 기록한다.
- Model명, 응답 수, 1024차원과 유한값 불변식을 매 호출마다 검증한다.
- 일반 테스트와 실제 모델 Benchmark를 분리하고 한 명령으로 JSON을 재현한다.
- 결과만으로 운영 기본값을 자동 변경하지 않고 후속 의사결정 근거로 남긴다.

## 3. 비교 Profile

| Profile | Chunk Size | Overlap | Overlap 비율 | 비고 |
|---|---:|---:|---:|---|
| `c400-o0` | 400 | 0 | 0% | 작은 Chunk 기준선 |
| `c400-o80` | 400 | 80 | 20% | 작은 Chunk 중첩 |
| `c800-o0` | 800 | 0 | 0% | 중간 Chunk 기준선 |
| `c800-o160` | 800 | 160 | 20% | 중간 Chunk 중첩 |
| `c1000-o0` | 1000 | 0 | 0% | 현재 크기의 중첩 없는 기준선 |
| `c1000-o200` | 1000 | 200 | 20% | 현재 운영 기본 Profile |
| `c1600-o0` | 1600 | 0 | 0% | 큰 Chunk 기준선 |
| `c1600-o320` | 1600 | 320 | 20% | 큰 Chunk 중첩 |

## 4. 결정적 경계 Corpus

### 4.1 문서와 Query

- 400·800·1000·1600 Code Point 경계마다 3개 Case를 만들어 총 12개 문서·질의를 사용한다.
- 각 문서는 약 2,200 Code Point의 중립적인 채움 Text와 한 개의 고유한 근거 문장으로 구성한다.
- 근거 시작점을 해당 경계 직전으로 고정해 Overlap이 없을 때 문장이 두 Chunk로 분리되게 한다.
- Query는 근거 문장만으로 답할 수 있는 고유한 한국어 질문이다.
- Seed와 문자열 Template을 고정해 같은 Commit에서 Corpus가 항상 같게 생성한다.

### 4.2 Ground Truth

Chunk가 다음 조건을 모두 만족할 때만 Relevant로 판정한다.

1. Query의 정답 문서에서 생성됐다.
2. `chunk.charStart <= evidenceStart`다.
3. `chunk.charEnd >= evidenceEnd`다.

일부 근거 조각만 포함한 Chunk는 Relevant로 취급하지 않는다. 따라서 Answer Coverage Ratio는
Chunking 단계에서 검색 가능한 완전한 근거가 보존됐는지를 나타낸다.

## 5. 측정 경계

### 5.1 실제 구성요소

- 제품 코드의 `FixedSizeChunker`
- 실제 Embedding Server의 `POST /embed/batch`
- 실제 `BAAI/bge-m3` 1024차원 Dense Vector
- Query와 Chunk Vector의 메모리 내 Exact Cosine Similarity

### 5.2 의도적인 제외

PostgreSQL·pgvector·HNSW를 사용하지 않는다. 이 작업의 독립 변수는 Chunk Size와 Overlap이며,
ANN 근사 오차와 DB 실행 계획을 포함하면 Chunking 품질과 Vector 검색 성능을 분리할 수 없다.
Exact·HNSW 성능과 Recall은 별도 Vector Benchmark에서 검증한다.

PDF·DOCX Parser, OCR, 권한 Filter와 RAG 답변 생성도 이번 측정에서 제외한다. Parser가 생성한
Page·Section 경계 보존 동작은 제품 Chunker 테스트 범위이며 이번 Corpus는 파라미터 효과만 격리한다.

## 6. 지표 계약

### 6.1 품질

| 지표 | 계산 |
|---|---|
| Answer Coverage Ratio | 완전한 근거 Chunk가 존재하는 Query 수 / 전체 Query 수 |
| Answer Hit@1 | 첫 번째 결과가 Relevant인 Query 비율 |
| Answer Hit@3 | 상위 3개 안에 Relevant가 있는 Query 비율 |
| MRR@10 | 상위 10개에서 첫 Relevant 순위 역수의 평균 |

동점은 Cosine Similarity 내림차순, 문서 ID 오름차순, Chunk Index 오름차순으로 고정한다.

### 6.2 비용

| 지표 | 계산 |
|---|---|
| Chunk Count | Profile이 생성한 전체 Chunk 수 |
| Chunk Code Points | 모든 Chunk Text의 Code Point 수 합계 |
| Duplicate Code Points | `Chunk Code Points - 원문 Code Points` |
| Duplicate Ratio | `Duplicate Code Points / 원문 Code Points` |
| Embedding Median·P95 | Profile별 Chunk Vector 생성 시간의 Round 통계 |
| Search Median·P95 | 전체 Query Exact Ranking 시간의 Round 통계 |
| Failure Count | HTTP·응답 계약·Vector 불변식 실패 수 |

## 7. 실행 공정성

1. Health Check 뒤 Query와 대표 Chunk를 임베딩해 Model을 Warm-up한다.
2. Query Vector는 한 번만 생성해 Profile별 Chunk 임베딩 비용과 분리한다.
3. 각 본 측정 Round에서 Profile 시작 순서를 한 칸씩 회전한다.
4. 한 HTTP 요청의 Text 수는 64개 이하로 나누고 모델 내부 Batch Size 기본값은 32로 둔다.
5. Chunk 생성과 임베딩, Exact 검색 시간을 구분해 기록한다.
6. 같은 Profile의 품질 지표는 Round마다 같아야 하며 다르면 측정을 실패시킨다.

기본값은 Warm-up 1회와 본 측정 2회다. 안정적인 통계가 필요할 때 System Property로 Round와
Batch Size를 변경할 수 있다.

## 8. Vector 불변식

모든 Batch 응답에서 다음을 검증한다.

- Model명이 `BAAI/bge-m3`다.
- 응답 Item 수와 요청 Text 수가 같다.
- Item Index가 요청 순서와 일치한다.
- 모든 Vector가 정확히 1024차원이다.
- 모든 원소가 NaN·Infinity가 아닌 유한값이다.
- Cosine 계산의 Vector Norm이 0보다 크다.

하나라도 어기면 부분 결과를 정상 수치로 기록하지 않고 Benchmark 전체를 실패시킨다.

## 9. 실행과 결과

일반 테스트는 실제 BGE-M3를 요구하지 않는다. 전용 Task만 외부 모델을 사용한다.

```bash
docker compose up -d embedding-server
./gradlew chunkQualityPerformanceTest
```

기본 결과는 다음 경로에 생성한다.

```text
build/reports/chunk-quality/chunk-quality-latest.json
```

확장 실행 예시는 다음과 같다.

```bash
./gradlew chunkQualityPerformanceTest \
  -Dchunk.quality.performance.rounds=3 \
  -Dchunk.quality.performance.batch-size=32 \
  -Dchunk.quality.performance.output=build/reports/chunk-quality/chunk-quality.json
```

## 10. 결과 해석

1. Failure Count가 0이고 Vector 불변식을 만족한 Profile만 비교한다.
2. Answer Coverage와 Hit@3가 가장 높은 Profile 집합을 확인한다.
3. 같은 품질이면 Duplicate Ratio와 Embedding P95가 낮은 Profile을 선호한다.
4. 한 Profile이 다른 Profile보다 품질은 낮지 않고 비용은 높지 않으면서 한 지표 이상 우수하면
   Pareto 후보로 표시한다.
5. 로컬 CPU 결과는 상대 비교 기준선이며 운영 SLO로 해석하지 않는다.
6. 운영 기본값 변경은 실제 사용자 Corpus 검증을 포함한 별도 의사결정으로 남긴다.

## 11. 커밋 분할

1. `docs: #147 Chunk Size·Overlap 품질 비교 설계`
2. `test: #147 Chunk 품질 지표와 Corpus 계약 추가`
3. `perf: #147 실제 BGE-M3 Chunk 품질 Benchmark 추가`
4. `perf: #147 Chunk Size·Overlap 실측 결과 기록`

## 12. 완료 조건

- 일반 `./gradlew test`가 실제 모델 없이 성공한다.
- 전용 Task가 8개 Profile과 12개 Query를 실제 BGE-M3에서 측정한다.
- 품질 4종, 비용 7종과 실행 환경·설정이 JSON에 기록된다.
- Vector 불변식과 품질 계산 계약이 자동 테스트로 보호된다.
- 실측 비교표, 결론, 한계와 재현 명령이 `docs/test-results/`에 기록된다.
- 제품 Chunking 기본 설정과 Pipeline 동작은 변경하지 않는다.
