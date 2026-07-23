# #56 검색 블록 완성 — pgvector 검색 + live check + POST /search 조립 (F-SEARCH-05/06/07)

closes #56

## 배경

Issue 3(임베딩 + 로깅), Issue 4(권한 pre-filter)에서 만든 부품을 조립해 검색 블록을 완성한다.
이 이슈에서 구현하는 세 단계는 순서 의존이 있어 하나의 이슈로 묶었다.

- **F-SEARCH-05**: pgvector `<=>` 코사인 거리로 Top-K 후보 추출
- **F-SEARCH-06**: 캐시 stale 방어를 위한 실시간 권한 재검증(live check)
- **F-SEARCH-07**: 검색 결과 저장 + `POST /search` API 조립

---

## 전체 흐름

```
POST /search
     │
     ▼
SearchFacade.search()
     │
     ├─ 1. QueryEmbeddingService.embed()
     │       질문 텍스트 → 1024차원 벡터 (Python 사이드카 /embed 호출)
     │
     ├─ 2. SearchQueryCommandService.createProcessing()
     │       search_queries INSERT (status = PROCESSING)
     │
     ├─ 3. AccessibleDocumentQueryService.findReadableDocumentIds()
     │       UNION 쿼리 → 접근 가능한 document_id 목록 (F-SEARCH-04 결과 재사용)
     │       └─ 빈 목록 → 즉시 SUCCESS(0건) 반환, 이하 생략
     │
     ├─ 4. VectorSearchQueryService.search()  ← F-SEARCH-05
     │       pgvector <=> Top-K 후보 추출
     │
     ├─ 5. PermissionQueryService.canReadDocument() × N  ← F-SEARCH-06
     │       후보별 live check — 캐시 stale 방어
     │       └─ false → 해당 후보 제거 (추가 조회 없음, MVP 정책)
     │
     ├─ 6. SearchResultCommandService.saveAll()  ← F-SEARCH-07
     │       live check 통과한 후보를 rank 순서로 search_results 저장
     │
     └─ 7. SearchQueryCommandService.markSuccess()
             latency_ms 기록, status → SUCCESS
             예외 발생 시 → markFailed()
```

---

## 신규 파일

### F-SEARCH-05: pgvector 검색

#### `VectorSearchRepository` + `VectorSearchRow`

```
domain/search/repository/VectorSearchRepository.java
domain/search/repository/VectorSearchRow.java
```

**핵심 쿼리:**

```sql
SELECT
    e.id          AS embedding_id,
    e.chunk_id    AS chunk_id,
    e.document_id AS document_id,
    dc.chunk_text AS chunk_text,
    dc.page_no    AS page_no,
    d.title       AS document_title,
    (e.vector <=> CAST(:queryVector AS vector)) AS distance
FROM embeddings e
  JOIN documents d        ON d.id  = e.document_id
  JOIN document_chunks dc ON dc.id = e.chunk_id
WHERE e.embedding_model_id = :modelId
  AND e.status             = 'ACTIVE'
  AND e.document_id        IN (:permittedIds)
  AND d.deleted_at         IS NULL
  AND d.status             = 'INDEXED'
  AND d.current_version_id = e.document_version_id
ORDER BY e.vector <=> CAST(:queryVector AS vector)
LIMIT :topK
```

**WHERE 조건 해설:**

| 조건 | 목적 |
|---|---|
| `embedding_model_id = :modelId` | 질문 임베딩과 동일한 모델 벡터만 비교 |
| `status = 'ACTIVE'` | STALE/DELETED 임베딩 제외 |
| `document_id IN (:permittedIds)` | 권한 pre-filter 결과 적용 |
| `d.deleted_at IS NULL` | soft delete 문서 제외 |
| `d.status = 'INDEXED'` | 색인 완료 문서만 검색 대상 |
| `d.current_version_id = e.document_version_id` | 구버전 임베딩 제외, 현재 버전만 검색 |

**queryVector 전달 방식:**

`float[]`를 서비스에서 `"[v1,v2,...,v1024]"` 문자열로 변환 후 `CAST(:queryVector AS vector)`로 pgvector 타입 변환.
VectorType(커스텀 Hibernate UserType)은 JPA 엔티티 매핑에는 쓰이지만 네이티브 쿼리 파라미터로는 직접 전달 불가하므로 문자열 변환을 택했다.

**`VectorSearchRow`** (Spring Data 네이티브 쿼리 프로젝션):

컬럼 alias가 `snake_case`일 때 Spring Data JPA가 `camelCase` getter로 자동 매핑한다. (예: `embedding_id` → `getEmbeddingId()`)

---

#### `VectorSearchCandidate` (내부 전달 DTO)

```
domain/search/dto/VectorSearchCandidate.java
```

```java
public record VectorSearchCandidate(
    Long embeddingId,
    Long chunkId,
    Long documentId,
    String chunkText,
    Integer pageNo,
    String documentTitle,
    BigDecimal similarityScore   // = 1 - cosineDistance
) {}
```

`VectorSearchRow`에서 변환 시 `similarityScore = 1 - distance`로 계산한다. 코사인 거리가 낮을수록(0에 가까울수록) 유사도가 높다.

---

#### `VectorSearchQueryService`

```
domain/search/service/query/VectorSearchQueryService.java
```

- `permittedIds`가 빈 목록이면 DB 쿼리 없이 즉시 `List.of()` 반환
- `float[]`를 `"[v1,v2,...]"` 문자열로 변환 후 repository 위임
- 결과를 `VectorSearchCandidate` 목록으로 변환해 반환

---

### F-SEARCH-06: live check

**`SearchFacade` 내부 처리 (별도 클래스 없음):**

```java
List<VectorSearchCandidate> verified = candidates.stream()
    .filter(c -> permissionQueryService.canReadDocument(userId, c.documentId()))
    .toList();
```

기존 `PermissionQueryService.canReadDocument()`를 그대로 재사용한다.
각 후보에 대해 5단계(OWNER → PUBLIC → USER캐시 → ROLE live → DEPT live)를 순서대로 체크한다.

**stale 캐시 방어 시나리오:**

```
권한 회수 직후 검색 시나리오:
1. user_document_access_cache에 아직 유효한 캐시가 남아있음
2. F-SEARCH-04 pre-filter → 해당 문서 ID가 permittedIds에 포함됨 (캐시 기반)
3. pgvector 검색 → 해당 문서 청크가 Top-K 후보에 포함됨
4. live check → canReadDocument 호출
   └─ 3단계 캐시 체크: invalidated_at이 설정돼 있으면 miss
   └─ 4/5단계 ROLE/DEPT live check: 권한이 실제로 없으면 false
   └─ false → 후보 제거, 결과에 미노출
```

`[PERM]` + `[SEARCH]` 타이밍 로그로 전 과정 추적 가능 (포트폴리오 증빙용).

---

### F-SEARCH-07: 결과 저장 + API 조립

#### `SearchResultCommandService`

```
domain/search/service/command/SearchResultCommandService.java
```

- live check 통과 후보 목록을 `rank_no = i + 1` 순서로 `search_results` 저장
- `EntityManager.getReference()`로 proxy 생성 — 불필요한 SELECT 방지
- `similarityScore` = `finalScore` (MVP는 키워드 점수 없음, dense vector만)

#### `SearchResponse` / `SearchResultItem`

```
domain/search/dto/response/SearchResponse.java
domain/search/dto/response/SearchResultItem.java
```

응답 구조:

```json
{
  "queryId": 42,
  "results": [
    {
      "rank": 1,
      "documentTitle": "Spring Boot 가이드",
      "chunkText": "벡터 검색은 ...",
      "pageNo": 3,
      "similarityScore": 0.923145
    }
  ]
}
```

#### `SearchRequest`

```
domain/search/dto/request/SearchRequest.java
```

| 필드 | 검증 | 기본값 |
|---|---|---|
| `queryText` | `@NotBlank` | — |
| `topK` | `@Min(1) @Max(20)` | null → `effectiveTopK()` = 5 |
| `collectionId` | 없음 (nullable) | null = 전체 검색 |

#### `SearchController`

```
domain/search/controller/SearchController.java
```

```
POST /search
Authorization: Bearer {token}
Content-Type: application/json

{ "queryText": "검색어", "topK": 5 }
```

#### `SearchFacade`

```
domain/search/service/SearchFacade.java
```

위 흐름 전체를 조율. `@Transactional` — 전체 검색 흐름이 하나의 트랜잭션.
예외 발생 시 `catch` 블록에서 `markFailed()` 호출 후 예외를 다시 던진다.

---

## 에러 케이스 정리

| 상황 | 처리 |
|---|---|
| 임베딩 서버 장애 | `EMBEDDING_SERVER_UNAVAILABLE(503)` + `markFailed` |
| 활성 임베딩 모델 없음 | `EMBEDDING_MODEL_NOT_CONFIGURED(500)` + `markFailed` |
| 접근 가능 문서 0건 | 빈 results 배열로 정상 응답(200), `markSuccess` |
| Top-K 결과 0건 | 빈 results 배열로 정상 응답(200), `markSuccess` |
| live check로 전체 탈락 | 빈 results 배열로 정상 응답(200), `markSuccess` |
| `queryText` 빈 문자열 | `@NotBlank` 검증 → 400 BAD_REQUEST |
| `topK` 범위 초과 | `@Min/@Max` 검증 → 400 BAD_REQUEST |
| 사용자 없음 | `USER_NOT_FOUND(404)` + `markFailed` |
| 지정한 컬렉션 없음 | `COLLECTION_NOT_FOUND(404)` + `markFailed` |

---

## 설계 결정

### 한 트랜잭션에 읽기 + 쓰기 혼합

`SearchFacade`는 `@Transactional`(읽기/쓰기 혼합)을 사용한다.
임베딩 서버 HTTP 호출이 트랜잭션 내에 포함되어 있는데, 이는 MVP 단계에서 단순성을 우선한 결정이다.
HTTP 호출 실패 시 `catch`에서 `markFailed`를 호출하고 예외를 다시 던지므로 `search_queries` 상태는 FAILED로 기록된다.

### live check를 pre-filter와 분리하는 이유

pre-filter(F-SEARCH-04)는 캐시 기반으로 빠른 UNION 쿼리이고,
live check(F-SEARCH-06)는 캐시 무효화 여부까지 확인하는 엄격한 권한 검증이다.
pre-filter 없이 live check만 하면 전체 INDEXED 문서에 N번의 권한 쿼리가 필요하므로 구조적으로 분리한다.

### MVP 정책: live check 탈락 후 보충 조회 없음

live check로 Top-K 후보 일부가 탈락해도 추가 검색 없이 그대로 반환한다.
다단계 검색(fill-up) 구현은 2단계에서 고려한다.

### currentVersion 조건 필수

`d.current_version_id = e.document_version_id` 조건이 없으면 이전 버전의 임베딩이 검색 결과에 포함될 수 있다.
문서 재업로드 → 색인 완료 → `current_version_id` 갱신 흐름에서 구버전 임베딩은 `status = STALE`로 변경되어야 하지만, 방어적으로 이 조건도 함께 건다.
