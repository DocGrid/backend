# #56 검색 블록 완성 — pgvector 검색 + live check + POST /search 조립 (F-SEARCH-05/06/07)

closes #56

---

## 배경

Issue 3(임베딩 + 로깅), Issue 4(권한 pre-filter)에서 만든 부품을 조립해 검색 블록을 완성한다.

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
     │       └─ 실패 시 → 로그조차 안 남고 즉시 503 (아직 search_queries row 없음)
     │
     ├─ 2. User / Collection 엔티티 조회 (FK 준비)
     │
     ├─ 3. SearchQueryCommandService.createProcessing()
     │       search_queries INSERT (status = PROCESSING) ← 이때부터 로그 남기 시작
     │
     ├─ 4. AccessibleDocumentQueryService.findReadableDocumentIds()  ← F-SEARCH-04 재사용
     │       UNION 쿼리 → 접근 가능한 document_id 목록
     │       └─ 빈 목록 → 즉시 SUCCESS(0건) 반환
     │
     ├─ 5. VectorSearchQueryService.search()  ← F-SEARCH-05
     │       pgvector <=> Top-K 후보 추출
     │
     ├─ 6. PermissionQueryService.canReadDocument() × N  ← F-SEARCH-06
     │       후보별 live check — 캐시 stale 방어
     │       └─ false → 해당 후보 제거
     │
     ├─ 7. SearchResultCommandService.saveAll()  ← F-SEARCH-07
     │       live check 통과한 후보를 rank 순서로 search_results 저장
     │
     └─ 8. SearchQueryCommandService.markSuccess()
             latency_ms 기록, status → SUCCESS
             예외 발생 시 → catch에서 markFailed() 후 예외 재전파
```

---

## 신규 파일

### F-SEARCH-05: pgvector 검색

#### VectorSearchRow.java — 네이티브 쿼리 프로젝션

**한 줄 요약**: pgvector SQL 결과 한 줄을 그대로 담는 raw 데이터 그릇.

```java
public interface VectorSearchRow {
    Long getEmbeddingId();      // 이 벡터(embeddings 테이블) 자체의 PK
    Long getChunkId();          // 원본 문서 조각(document_chunks)의 ID
    Long getDocumentId();       // 이 청크가 속한 원본 문서의 ID
    String getChunkText();      // 매칭된 청크의 실제 원문 텍스트
    Integer getPageNo();        // 원본 문서에서 몇 페이지 (페이지 없으면 null)
    String getDocumentTitle();  // 원본 문서의 제목 — 출처 표시용
    Double getDistance();       // pgvector <=> 코사인 거리값 (0에 가까울수록 유사)
                                // VectorSearchCandidate에서 1 - distance로 유사도로 변환됨
}
```

SQL의 `snake_case` 컬럼 alias(`embedding_id` 등)를 Spring Data JPA가 `camelCase` getter(`getEmbeddingId()`)로 자동 매핑. 인터페이스만 선언하면 Spring이 런타임에 구현체를 만들어 줌.

---

#### VectorSearchRepository.java — 벡터 검색 심장부

**한 줄 요약**: 권한 필터된 문서 안에서만 pgvector 코사인 거리로 Top-K를 뽑아오는 실제 검색 쿼리.

```java
public interface VectorSearchRepository extends JpaRepository<Embedding, Long> {

    @Query(value = """
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
        """, nativeQuery = true)
    List<VectorSearchRow> findTopK(
        @Param("queryVector") String queryVector,
        @Param("modelId") Long modelId,
        @Param("permittedIds") List<Long> permittedIds,
        @Param("topK") int topK
    );
}
```

**WHERE 조건 하나하나의 의미:**

| 조건 | 목적 |
|---|---|
| `embedding_model_id = :modelId` | 질문 임베딩 모델 = 문서 임베딩 모델 원칙 강제, 다른 모델 벡터 혼입 방지 |
| `status = 'ACTIVE'` | 재임베딩으로 폐기된 벡터 제외 |
| `document_id IN (:permittedIds)` | Issue 4 pre-filter 산출물이 여기 들어옴 — 권한 필터 → 벡터 검색 순서가 물리적으로 강제되는 지점 |
| `d.deleted_at IS NULL` | soft delete 문서 제외 |
| `d.status = 'INDEXED'` | 색인 완료 문서만 검색 대상 |
| `d.current_version_id = e.document_version_id` | 최신 버전 임베딩만 검색 대상, 구버전 혼입 방지 |

**queryVector 전달 방식:**
`float[]`를 서비스에서 `"[v1,v2,...,v1024]"` 문자열로 변환 후 `CAST(:queryVector AS vector)`로 pgvector 타입 변환. VectorType(커스텀 Hibernate UserType)은 JPA 엔티티 매핑에는 쓰이지만 네이티브 쿼리 파라미터로는 직접 전달 불가하므로 문자열 변환을 택했다.

---

#### VectorSearchCandidate.java — 거리 → 유사도 변환

**한 줄 요약**: raw 검색 결과(VectorSearchRow)를 사람이 이해하기 쉬운 유사도 점수로 변환한 검색 후보 객체.

```java
public record VectorSearchCandidate(
    Long embeddingId, Long chunkId, Long documentId,
    String chunkText, Integer pageNo, String documentTitle,
    BigDecimal similarityScore
) {
    public static VectorSearchCandidate from(VectorSearchRow row) {
        BigDecimal score = BigDecimal.ONE
            .subtract(BigDecimal.valueOf(row.getDistance()))
            .max(BigDecimal.ZERO)
            .setScale(6, RoundingMode.HALF_UP);
        return new VectorSearchCandidate(
            row.getEmbeddingId(), row.getChunkId(), row.getDocumentId(),
            row.getChunkText(), row.getPageNo(), row.getDocumentTitle(), score
        );
    }
}
```

- `BigDecimal` 사용 이유: `double` 부동소수점 미세 오차 누적 방지.
- `1 - distance`: pgvector `<=>`는 거리(0에 가까울수록 유사)를 반환. 사람이 이해하기 쉬운 유사도(1에 가까울수록 유사)로 뒤집음.
- `.max(BigDecimal.ZERO)`: 코사인 거리는 이론상 0~2 범위라, `1 - distance`가 음수가 될 수 있음(각도가 90도 초과) → 0으로 clamp해 방어.
- `.setScale(6, RoundingMode.HALF_UP)`: 소수점 6자리 반올림.
- 정적 팩토리 메서드(`from`): "이 객체는 VectorSearchRow로부터 변환됐다"는 의도를 이름으로 드러냄.

---

#### VectorSearchQueryService.java

**한 줄 요약**: 질문 벡터와 권한 목록을 받아 실제 벡터 검색을 실행하고, 결과를 후보 객체로 변환하는 서비스.

```java
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchQueryService {

    private final VectorSearchRepository vectorSearchRepository;

    public List<VectorSearchCandidate> search(
        float[] queryVector, Long modelId, List<Long> permittedIds, int topK
    ) {
        if (permittedIds.isEmpty()) {
            log.debug("[SEARCH] permittedIds empty — skip vector search");
            return List.of();
        }

        String vectorStr = toVectorString(queryVector);
        return vectorSearchRepository.findTopK(vectorStr, modelId, permittedIds, topK)
            .stream()
            .map(VectorSearchCandidate::from)
            .toList();
    }

    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
```

`permittedIds`가 비면 DB 조회 자체를 생략 — 불필요한 쿼리 방지. `toVectorString()`은 `float[1024]`를 pgvector 표준 문자열 표기(`[v1,v2,...]`)로 변환하며, `VectorType.nullSafeSet()`이나 Python 서버 응답 포맷과 같은 형식이라 서로 호환됨.

---

### F-SEARCH-06: live check

**`SearchFacade` 내부 처리 (별도 클래스 없음):**

```java
List<VectorSearchCandidate> verified = candidates.stream()
    .filter(c -> permissionQueryService.canReadDocument(userId, c.documentId()))
    .toList();
log.info("[SEARCH] live check userId={} before={} after={} liveMs={}",
    userId, candidates.size(), verified.size(), latencyMs(liveStart));
```

기존 `PermissionQueryService.canReadDocument()`를 재사용. 각 후보에 대해 5단계(OWNER → PUBLIC → USER캐시 → ROLE live → DEPT live)를 순서대로 체크.

**stale 캐시 방어 시나리오:**

```
권한 회수 직후 검색 시나리오:
1. user_document_access_cache에 아직 유효한 캐시가 남아있음
2. F-SEARCH-04 pre-filter → 해당 문서 ID가 permittedIds에 포함됨 (캐시 기반)
3. pgvector 검색 → 해당 문서 청크가 Top-K 후보에 포함됨
4. live check → canReadDocument() 호출
   └─ 3단계 캐시 체크: invalidated_at이 설정돼 있으면 miss
   └─ 4/5단계 ROLE/DEPT live check: 권한이 실제로 없으면 false
   └─ false → 후보 제거, 결과에 미노출
```

before/after 로그가 "캐시 stale을 실제로 잡아냈다"는 증거로 남음.

---

### F-SEARCH-07: 결과 저장 + API 조립

#### SearchResultCommandService.java

**한 줄 요약**: live check까지 통과한 최종 검색 결과를 순위대로 `search_results`에 저장하는 서비스.

```java
@Transactional
@Service
@RequiredArgsConstructor
public class SearchResultCommandService {

    private final SearchResultRepository searchResultRepository;
    private final EntityManager entityManager;

    public void saveAll(SearchQuery searchQuery, List<VectorSearchCandidate> candidates) {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate c = candidates.get(i);
            results.add(SearchResult.builder()
                .query(searchQuery)
                .chunk(entityManager.getReference(DocumentChunk.class, c.chunkId()))
                .embedding(entityManager.getReference(Embedding.class, c.embeddingId()))
                .rankNo(i + 1)
                .similarityScore(c.similarityScore())
                .finalScore(c.similarityScore())  // HYBRID 검색 확장 대비 컬럼 선반영
                .matchedText(c.chunkText())
                .build());
        }
        searchResultRepository.saveAll(results);
    }
}
```

- `entityManager.getReference()` vs `findById()`: `findById()`는 실제 SELECT 쿼리가 나가지만, `getReference()`는 프록시만 만들어 FK ID값만 세팅 — 존재 여부를 확인할 필요 없는 FK 연결에서 불필요한 SELECT를 피할 수 있음. Top-K가 5개면 SELECT 5번을 아예 안 하게 됨.
- `.query(searchQuery)`: 이미 완전한 형태로 갖고 있는 객체(방금 `createProcessing()`에서 만든 것)라 프록시를 새로 만들 필요 없음.
- `rankNo = i + 1`: candidates가 SQL `ORDER BY + live check`를 거쳐 이미 정렬된 순서라 별도 정렬 불필요.
- `finalScore`: MVP는 VECTOR만 지원하지만 나중에 HYBRID(키워드+벡터) 추가 시 가중합산 점수가 필요해질 것을 대비해 컬럼 구조 미리 준비.

---

#### SearchFacade.java — 전체 조율자

**한 줄 요약**: 임베딩 → 권한필터 → 벡터검색 → live check → 저장까지 검색 블록 전체 흐름을 순서대로 지휘하는 클래스.

```java
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchFacade {

    // ... 8개 의존성 주입 ...

    public SearchResponse search(Long userId, SearchRequest request) {
        long start = System.currentTimeMillis();

        // 1. 질문 임베딩 — 여기서 실패하면 로그조차 안 남고 즉시 503
        EmbedResult embedResult = queryEmbeddingService.embed(request.queryText());

        // 2. FK용 엔티티 조회
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        DocumentCollection collection = resolveCollection(request.collectionId());

        // 3. search_queries PROCESSING 저장
        SearchQuery searchQuery = searchQueryCommandService.createProcessing(
            user, collection, request.queryText(),
            embedResult.model(), embedResult.vector(), request.effectiveTopK()
        );

        try {
            // 4. 권한 pre-filter
            List<Long> permittedIds = accessibleDocumentQueryService
                .findReadableDocumentIds(userId, request.collectionId());

            if (permittedIds.isEmpty()) {
                log.info("[SEARCH] no accessible documents userId={}", userId);
                searchQueryCommandService.markSuccess(searchQuery, latencyMs(start));
                return SearchResponse.empty(searchQuery.getId());
            }

            // 5. pgvector Top-K 후보 추출
            List<VectorSearchCandidate> candidates = vectorSearchQueryService.search(
                embedResult.vector(), embedResult.model().getId(),
                permittedIds, request.effectiveTopK()
            );

            // 6. live check — 캐시 stale 방어
            long liveStart = System.currentTimeMillis();
            List<VectorSearchCandidate> verified = candidates.stream()
                .filter(c -> permissionQueryService.canReadDocument(userId, c.documentId()))
                .toList();
            log.info("[SEARCH] live check userId={} before={} after={} liveMs={}",
                userId, candidates.size(), verified.size(), latencyMs(liveStart));

            // 7. search_results 저장
            searchResultCommandService.saveAll(searchQuery, verified);

            int latency = latencyMs(start);
            searchQueryCommandService.markSuccess(searchQuery, latency);
            log.info("[SEARCH] done queryId={} results={} latencyMs={}",
                searchQuery.getId(), verified.size(), latency);

            return SearchResponse.of(searchQuery.getId(), verified);

        } catch (Exception e) {
            searchQueryCommandService.markFailed(searchQuery, e.getMessage());
            throw e;
        }
    }
}
```

단계별 핵심 포인트:

- **1단계**: 임베딩 실패 시 `search_queries` row조차 없어서 로그로도 안 남고 API가 바로 503 반환.
- **3단계**: 이 시점부터 `search_queries` row가 생겨서 이후 실패는 FAILED로 기록됨.
- **4단계**: 접근 가능 문서 없으면 벡터 검색 생략 후 즉시 빈 결과 반환 — 실패가 아닌 정상 흐름이라 `markSuccess()`.
- **5단계**: `permittedIds`를 그대로 벡터 검색 조건으로 사용 — 코드 순서 자체가 "권한 필터 → 벡터 검색" 원칙을 물리적으로 보여줌.
- **6단계**: pre-filter는 캐시 기반이라 권한이 실제로 회수됐을 수 있음. Top-K(소수)만 대상으로 `canReadDocument()` 단건 검증을 다시 돌려서 방어.
- **catch**: 4~7단계 어디서든 예외 발생 시 `markFailed()` 후 예외를 다시 던져 컨트롤러가 적절한 HTTP 코드로 응답.

---

#### SearchRequest.java

```java
public record SearchRequest(
    @NotBlank String queryText,
    @Min(1) @Max(20) Integer topK,
    @Positive Long collectionId
) {
    private static final int DEFAULT_TOP_K = 5;

    public int effectiveTopK() {
        return topK != null ? topK : DEFAULT_TOP_K;
    }
}
```

- `@NotBlank`: null/빈 문자열/공백만 있는 문자열 모두 차단.
- `effectiveTopK()`: 반환 타입을 `int`(원시 타입)로 둬서 이 메서드를 거친 이후 코드는 다시 null 체크를 안 해도 됨.

---

#### SearchResponse.java / SearchResultItem.java

```java
public record SearchResponse(Long queryId, List<SearchResultItem> results) {

    public static SearchResponse of(Long queryId, List<VectorSearchCandidate> candidates) {
        List<SearchResultItem> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            items.add(SearchResultItem.of(i + 1, candidates.get(i)));
        }
        return new SearchResponse(queryId, List.copyOf(items));
    }

    public static SearchResponse empty(Long queryId) {
        return new SearchResponse(queryId, List.of());
    }
}
```

- `queryId`: RAG 블록이 이 값을 이어받아 답변 생성 시 참조 키로 사용할 예정.
- `empty()`: "검색을 아예 안 한 지름길" 케이스임을 이름으로 드러냄.

**실제 JSON 응답 예시:**

```json
{
  "queryId": 100,
  "results": [
    {
      "rank": 1,
      "documentTitle": "인사규정",
      "chunkText": "휴가 신청은 사내 포털에서...",
      "pageNo": 3,
      "similarityScore": 0.912847
    }
  ]
}
```

---

#### SearchController.java

```java
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchFacade searchFacade;

    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestBody @Valid SearchRequest request
    ) {
        return ResponseUtils.ok(searchFacade.search(userId, request));
    }
}
```

컨트롤러는 실제 로직을 하나도 모르고 SearchFacade에 위임만 함. HTTP 요청/응답 변환, 인증 정보 추출(`@CurrentUser`), 검증 트리거(`@Valid`)만 담당.

---

## 코사인 거리 개념

임베딩 모델이 텍스트를 1024개 숫자로 바꾸는 걸 "화살표 하나"로 생각하면 이해하기 쉽다. 의미가 비슷한 문장은 비슷한 방향을 가리키는 화살표가 되고, 코사인 거리는 이 두 화살표 사이의 각도를 잰다 (화살표 길이는 무시하고 방향만 본다).

| 각도 | 코사인 거리 | 코사인 유사도 | 의미 |
|---|---|---|---|
| 0도 (같은 방향) | 0 | 1 | 완전히 같은 의미 |
| 90도 (직각) | 1 | 0 | 서로 무관 |
| 180도 (반대 방향) | 2 | -1 | 정반대 의미 |

코드에서:
```text
similarityScore = max(0, 1 - distance)
```

- `1 - distance`: pgvector `<=>`가 반환하는 거리를 유사도로 뒤집음.
- `max(0, ...)`: 각도가 90도를 넘어가면 이론상 음수 유사도가 나올 수 있어서 0으로 clamp해 방어.

`VectorSearchRepository`가 하는 일은 결국 "질문 벡터라는 화살표 하나를 그려놓고, DB에 있는 수많은 청크 벡터(화살표들) 중 각도가 가장 가까운 것들을 순서대로 뽑아오는 것"이다.

---

## 에러 케이스 정리

| 상황 | 처리 |
|---|---|
| 임베딩 서버 장애 | `EMBEDDING_SERVER_UNAVAILABLE(503)` — createProcessing 이전 실패라 markFailed 미호출 |
| 활성 임베딩 모델 없음 | `EMBEDDING_MODEL_NOT_CONFIGURED(500)` — createProcessing 이전 실패라 markFailed 미호출 |
| 접근 가능 문서 0건 | 빈 results 배열 정상 응답(200), `markSuccess` |
| Top-K 결과 0건 | 빈 results 배열 정상 응답(200), `markSuccess` |
| live check로 전체 탈락 | 빈 results 배열 정상 응답(200), `markSuccess` |
| `queryText` 빈 문자열 | `@NotBlank` 검증 → 400 BAD_REQUEST |
| `topK` 범위 초과 | `@Min/@Max` 검증 → 400 BAD_REQUEST |
| 사용자 없음 | `USER_NOT_FOUND(404)` + `markFailed` |
| 지정한 컬렉션 없음 | `COLLECTION_NOT_FOUND(404)` + `markFailed` |

---

## 설계 결정 요약

**한 트랜잭션에 읽기 + 쓰기 + HTTP 호출 혼합**
`SearchFacade`는 `@Transactional`(읽기/쓰기 혼합). 임베딩 서버 HTTP 호출이 트랜잭션 내에 포함되어 있어 DB 커넥션 점유 시간이 길어지는 트레이드오프. MVP 단계에서 단순성 우선한 결정. 추후 트랜잭션 분리 검토 대상.

**live check를 pre-filter와 분리하는 이유**
pre-filter(F-SEARCH-04)는 캐시 기반으로 빠른 UNION 쿼리이고, live check(F-SEARCH-06)는 캐시 무효화 여부까지 확인하는 엄격한 권한 검증. pre-filter 없이 live check만 하면 전체 INDEXED 문서에 N번의 권한 쿼리가 필요하므로 구조적으로 분리.

**MVP 정책: live check 탈락 후 보충 조회 없음**
live check로 Top-K 후보 일부가 탈락해도 추가 검색 없이 그대로 반환. 다단계 검색(fill-up) 구현은 2단계에서 고려.

**currentVersion 조건 필수**
`d.current_version_id = e.document_version_id` 조건이 없으면 이전 버전의 임베딩이 검색 결과에 포함될 수 있음. 문서 재업로드 → 색인 완료 → `current_version_id` 갱신 흐름에서 구버전 임베딩은 `status = STALE`로 변경되어야 하지만, 방어적으로 이 조건도 함께 건다.

---

## 남은 이슈 / TODO

### 코드

- `SearchQueryCommandService.markFailed()`의 `REQUIRES_NEW`와 아직 커밋 안 된 `createProcessing()` row 간의 트랜잭션 상호작용 통합 테스트로 검증 필요.
- live check 탈락 후 보충 조회(fill-up) 미구현 — 현재는 줄어든 채 그대로 반환.
- 임베딩 서버 HTTP 호출이 `@Transactional` 안에 포함되어 DB 커넥션 점유 시간이 길어지는 트레이드오프 — 트랜잭션 분리 검토.
- `IN (:permittedIds)` 파라미터가 매우 커질 경우(수천 건 이상) 성능 영향 가능성 — MVP 규모에서는 문제 없음.

### 기능 확장 (2단계)

- KEYWORD / HYBRID 검색 타입 추가 (현재 VECTOR만 지원. `finalScore` 컬럼은 이를 대비해 미리 준비됨).
- `@DataJpaTest`로 UNION 쿼리 및 pgvector 쿼리 통합 테스트 추가.
- end-to-end 테스트: 권한 회수 직후(캐시 stale) 검색 시 해당 문서 미노출 확인.

### 다음 단계

검색 블록(Issue 1~5)으로 `POST /search`가 완성되어, 권한 필터링 후 유사도 순으로 관련 문서 조각을 찾아주는 기능은 끝났다. 다음은 RAG 블록 — 이 검색 결과(chunk 후보들)를 받아 로컬 LLM(qwen2.5:3b, Ollama)으로 자연어 답변을 생성하는 단계다.
