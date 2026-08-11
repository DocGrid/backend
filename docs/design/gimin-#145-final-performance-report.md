# 인덱싱·Vector 검색 최종 통합 성능 리포트 설계

- 관련 이슈: [#145](https://github.com/DocGrid/backend/issues/145)
- 작성일: 2026-08-11
- 상태: 구현 중

## 1. 배경

Job Claim, 실제 BGE-M3 배치, Exact·HNSW 검색, Worker 수평 확장, Queue Backpressure,
PDF·DOCX 전체 인덱싱 부하 결과는 각각 재현 가능한 문서로 남아 있다. 그러나 실험 목적과 환경이
서로 달라 전체 병목의 이동, 권장 기본값과 운영 전 남은 검증을 한 문서에서 설명하기 어렵다.

이번 작업은 기존 Benchmark를 다시 실행하거나 서로 다른 Workload를 하나의 순위로 합치지 않는다.
검증된 결과에서 핵심 수치와 출처를 정규화하고, 같은 실험 안에서만 비교하는 그래프와 최종 결론을
재현 가능한 형태로 제공한다.

## 2. 목표와 성공 기준

- 개별 결과 문서의 대표 수치를 하나의 JSON 데이터 계약으로 관리한다.
- 모든 데이터 행은 저장소 안의 원본 결과 문서로 추적할 수 있다.
- Python 표준 라이브러리만으로 GitHub에서 렌더링되는 SVG를 결정적으로 생성한다.
- `--check` 실행이 데이터 계약 오류, 사라진 출처와 생성 결과 불일치를 탐지한다.
- 최종 리포트가 비교표, 그래프, 병목 분석, 권장값, 한계와 후속 검증을 포함한다.

## 3. 비교 경계

### 3.1 같은 그래프에서 비교하는 범위

| 영역 | 고정 조건 | 비교 변수 |
|---|---|---|
| Job Claim | OpenSQL 17.8, 5,000 Job, 5회 | Worker 1·5·10·20·40 |
| BGE-M3 | 실제 `BAAI/bge-m3`, CPU, 256 Text, 3회 | Batch 1·4·8·16·32·64 |
| Vector 검색 | PostgreSQL 17.8, pgvector 0.8.1, `vector(1024)`, topK 10 | 2천·1만·5만 Row, Exact·HNSW |
| Worker 확장 | TXT 16문서, 문서당 8 Chunk, Batch 32 | Worker 수와 실행 Slot |
| Backpressure | TXT 800자, Pool 4, Worker Slot 8 | 16·32·64·128문서와 업로더 수 |
| 실제 문서 E2E | PDF·DOCX 혼합, 문서당 4 Chunk, Slot 2, Batch 32 | 50·100문서 |

### 3.2 직접 비교하지 않는 범위

- TXT 800자 Backpressure 처리량과 TXT 6,400자 Worker 처리량
- PDF·DOCX 문서/분과 TXT 문서/분
- Apple Silicon의 `linux/amd64` OpenSQL Container와 공급사 지원 Rocky Linux 원격 서버의 절대 성능
- 합성 Random Vector Recall과 실제 문서 Corpus의 검색 품질
- 서로 다른 실행 일자·장비의 수치를 하나의 통합 점수로 환산한 값

리포트는 각 영역 안의 상대 변화만 결론의 근거로 사용한다. 영역 간 수치는 병목이 어느 단계로
이동했는지 설명하는 참고값이며 직접적인 우열 비교가 아니다.

## 4. 데이터 계약

정규화 JSON은 다음 공통 정보를 가진다.

- Schema Version과 생성 목적
- Benchmark별 원본 Markdown 경로와 환경·Workload 설명
- X축 Label과 각 Series의 이름·단위·값
- 결과 문서에 표시할 대표 수치와 해석 경계

생성기는 다음 불변식을 검증한다.

1. Schema Version과 필수 Benchmark 6종이 존재한다.
2. 모든 원본 경로가 저장소 내부의 일반 파일을 가리킨다.
3. Series 길이가 X축 길이와 같고 모든 수치가 유한한 0 이상의 값이다.
4. 그래프 정의가 등록된 Series와 단위만 참조한다.
5. 출력 파일 이름이 중복되지 않고 `docs/test-results/assets/` 아래에만 생성된다.

## 5. 그래프 설계

| 파일 | 목적 | 표현 |
|---|---|---|
| `claim-throughput-tail-latency.svg` | Claim 동시성의 처리량과 Tail Latency 균형 | TPS와 p99 이중 축 Line |
| `bge-batch-throughput-latency.svg` | Batch Size의 처리량 포화와 요청 지연 | Text/s와 p95 이중 축 Line |
| `vector-exact-hnsw-latency-recall.svg` | HNSW 속도 향상과 Recall 비용 | 검색 p95 Log 축 Bar + Recall Line |
| `worker-horizontal-scaling.svg` | 실행 Slot 증가의 효율과 지연 이동 | 문서/분 Line + Queue·처리 p95 Bar |
| `queue-backpressure.svg` | 부하 증가 시 처리량 Plateau와 Queue 증가 | 문서/분과 Queue p95 이중 축 Line |
| `pdf-docx-e2e-load.svg` | 실제 문서 수 증가 시 처리량과 E2E 구성 | 문서/분 Line + Queue·처리 p95 Bar |

SVG에는 `title`, `desc`, 범례, 축 이름과 단위를 포함한다. 색상만으로 Series를 구분하지 않도록
Line Marker와 범례를 함께 제공하고, 정확한 수치는 Markdown 표에서 확인할 수 있게 한다.

## 6. 생성과 검증 흐름

1. 생성기가 정규화 JSON과 저장소 Root를 읽는다.
2. Schema, 수치, Series 길이와 원본 문서 경로를 검증한다.
3. 고정된 크기·색상·정렬 규칙으로 SVG 6개를 Memory에서 생성한다.
4. 기본 실행은 대상 Directory에 결과를 기록한다.
5. `--check` 실행은 Memory 결과와 Commit된 SVG를 Byte 단위로 비교한다.
6. 불일치 시 재생성 명령과 대상 파일을 포함한 오류로 실패한다.

```bash
python3 scripts/performance/generate_final_performance_report.py
python3 scripts/performance/generate_final_performance_report.py --check
```

## 7. 최종 리포트 구조

- Executive Summary와 단계별 핵심 결론
- 실험별 환경·Workload 비교 경계
- 6개 비교표와 6개 그래프
- Claim, Embedding, Vector, Worker, Queue, 실제 문서 E2E 병목 분석
- 측정으로 확정할 수 있는 권장값과 확정할 수 없는 운영값
- 공급사 지원 Rocky Linux 원격 OpenSQL 검증 등 남은 작업
- 원본 결과 문서와 재생성 명령

## 8. 제외 범위

- 기존 Benchmark 재실행과 원시 JSON 복구
- 제품 코드, Database Schema 또는 운영 설정 변경
- 현재 측정값을 운영 SLO로 확정
- 새로운 성능 임계값으로 CI를 차단
- 공급사 지원 Rocky Linux 환경의 최종 호환성 승인
- Grafana Dashboard 또는 외부 Chart Service 추가

## 9. 커밋 분할

1. `docs: #145 최종 통합 성능 리포트 설계`
2. `docs: #145 통합 성능 원본 데이터 추가`
3. `build: #145 성능 그래프 생성 및 검증 도구 추가`
4. `docs: #145 최종 성능 비교표와 그래프 기록`

## 10. 완료 조건

- 원본 결과 8개에서 선택한 핵심 수치가 정규화 JSON과 일치한다.
- SVG 6개가 GitHub에서 바로 표시되고 생성 결과가 결정적이다.
- `--check`가 정상 결과에서 성공하고 고의 불일치에서 실패한다.
- 최종 리포트가 비교 가능한 범위와 비교 금지 범위를 명시한다.
- 병목과 권장 기본값의 근거가 표·그래프·원본 문서로 추적된다.
