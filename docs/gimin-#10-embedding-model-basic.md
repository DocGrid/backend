# Issue #10 기본 임베딩 모델 등록 및 조회

## 1. 목적

문서 업로드 시 생성되는 `EmbeddingJob`이 사용할 기본 임베딩 모델 메타데이터를 등록하고 조회한다.

이번 범위는 실제 모델 호출이 아니라 다음 계약을 제공하는 것이다.

```text
local/test 환경의 기본 모델 Seed
→ 내부 서비스가 활성 모델 Entity 조회
→ 업로드 시 EmbeddingJob.embedding_model_id 고정
→ 외부 API는 공개 가능한 모델 정보만 DTO로 반환
```

## 2. 기본 모델 정책

기본 모델은 다음 조건을 동시에 만족하는 모델이다.

```text
is_active = true
is_searchable = true
```

두 필드의 역할은 다르다.

- `is_active`: 새 EmbeddingJob에 사용할 수 있는가
- `is_searchable`: 해당 모델로 생성한 기존 Vector를 현재 검색에 사용할 수 있는가

모델을 교체할 때 기존 모델을 `false + true`로 유지하면 신규 작업에는 사용하지 않으면서 기존 Vector 검색은 계속 허용할 수 있다.

## 3. DB 제약

모델 차원은 양수여야 하며, 활성·검색 가능 모델은 최대 하나만 존재해야 한다.

```sql
ALTER TABLE embedding_models
    ADD CONSTRAINT ck_embedding_models_dimension_positive
    CHECK (dimension > 0);

CREATE UNIQUE INDEX uk_embedding_models_one_active_searchable
    ON embedding_models ((1))
    WHERE is_active = TRUE
      AND is_searchable = TRUE;
```

일반 `UNIQUE(is_active, is_searchable)`는 모든 Boolean 조합을 한 건으로 제한하므로 사용하지 않았다. Partial Unique Index는 `true + true`인 행만 같은 Key로 인덱싱한다.

## 4. Mock Seed

local/test Profile에서는 다음 모델을 멱등 Seed로 등록한다.

| 항목 | 값 |
|---|---|
| Provider | `MOCK` |
| Model name | `mock-bge-m3` |
| Version | `v1` |
| Dimension | `1024` |
| Distance metric | `COSINE` |
| Active | `true` |
| Searchable | `true` |

Seed는 `db/seed` Flyway Location을 사용하는 local/test에서만 적용되며 운영 Profile에는 적용하지 않는다.

## 5. 조회 구조

내부 도메인 로직과 외부 API의 반환 타입을 분리했다.

```text
EmbeddingModelQueryService.getActiveModel()
→ EmbeddingJob 생성에 필요한 Entity 반환

GET /api/embedding-models/active
→ 외부 공개용 EmbeddingModelResponse 반환
```

업로드 시점에 `EmbeddingJob`이 모델 ID를 저장하므로, 이후 기본 모델이 바뀌어도 이미 접수된 작업이 다른 모델로 실행되지 않는다.

외부 응답에는 Provider, 모델명, 버전, 차원, 거리 계산 방식 등 필요한 메타데이터만 포함하고 내부 설정과 감사 필드는 노출하지 않는다.

## 6. 예외 처리

- 기본 모델이 없으면 설정 오류로 처리
- 데이터가 비정상적으로 여러 건이면 중복 설정 오류로 처리
- 내부 설정 문제와 정상적인 사용자 요청 오류를 구분

DB는 최대 한 개를 보장하고, 서비스는 0개인 상태도 명시적으로 처리한다.

## 7. 검증

- Entity의 필수값과 차원 검증
- Repository의 활성·검색 가능 모델 조회
- Service의 정상·미설정·중복 결과 처리
- Controller의 응답 DTO와 오류 응답
- V27 Migration과 반복 가능한 Seed 적용
- 애플리케이션 재기동 후 Mock 모델이 한 건으로 유지되는지 확인

Partial Unique Index의 실제 동시 트랜잭션 검증과 Benchmark 결과는 [Issue #12 테스트 결과](./test-results/gimin-#12-embedding-model-concurrency-verification.md)에 별도로 정리했다.
