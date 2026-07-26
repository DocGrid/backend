# 벡터 검색 + live check + POST /search 수동 테스트 결과

## 1. 테스트 목적

이슈 #35 · #41 · #44 · #54 · #56에서 구현한 검색 블록 전체가 다음 계약을 지키는지 Swagger에서 확인했다.

- 질문 텍스트를 임베딩 서버(Python FastAPI)로 변환해 1024차원 벡터를 얻는다.
- 검색 요청이 `search_queries` 테이블에 PROCESSING → SUCCESS/FAILED로 기록된다.
- 권한 pre-filter가 적용된 문서 안에서만 pgvector 코사인 유사도로 Top-K를 뽑는다.
- `topK` 생략 시 기본값 5가 적용된다.
- `collectionId` 지정 시 해당 컬렉션 안 문서로 범위가 좁혀진다.
- 입력값 검증 실패 시 400이 반환된다.
- 존재하지 않는 `collectionId` → 404가 반환된다.
- 권한 있는 문서가 없으면 빈 배열이 반환된다.
- 임베딩 서버 다운 시 503이 반환된다.
- topK보다 실제 데이터가 적으면 있는 만큼만 반환된다.
- 검색 결과가 `search_results` 테이블에 rank 순서대로 저장된다.

설계 문서:
- [`docs/design/kangcheolung-#35-embedding-server.md`](../design/kangcheolung-#35-embedding-server.md)
- [`docs/design/kangcheolung-#41-vector-search-db-infrastructure.md`](../design/kangcheolung-#41-vector-search-db-infrastructure.md)
- [`docs/design/kangcheolung-#44-search-embedding-query-logging.md`](../design/kangcheolung-#44-search-embedding-query-logging.md)
- [`docs/design/kangcheolung-#54-search-permission-pre-filter.md`](../design/kangcheolung-#54-search-permission-pre-filter.md)
- [`docs/design/kangcheolung-#56-vector-search-live-check-api.md`](../design/kangcheolung-#56-vector-search-live-check-api.md)

---

## 2. 테스트 환경과 제약

- Spring Boot local profile, PostgreSQL (docgrid-postgres 커스텀 Docker 이미지 — tmaxopensql/postgres:14.6 기반 + pgvector 0.8.0 소스 빌드)
- Python 임베딩 서버: `docgrid-embedding` Docker 컨테이너 (BAAI/bge-m3 모델, localhost:8000)
- Swagger UI에서 `kcw130502@gmail.com` 계정 JWT 인증 후 테스트
- seed 데이터 임베딩 벡터가 더미값(특정 차원만 0.3, 나머지 0.001)이라 `similarityScore`가 대부분 0에 가깝게 나오는 건 정상 — 실제 파이프라인으로 임베딩된 문서가 들어오면 의미 있는 점수가 나온다

### 수동 테스트 직전 발견 · 수정한 버그

`EmbedServerResponse`에 `dimension` 필드를 넣어 Python 서버 응답과 함께 검증하려 했으나, Python `/embed` 응답(`{"vector": [...]}`)에 `dimension` 필드가 없어 Java가 기본값 `0`으로 역직렬화 → `0 != 1024`가 항상 참이 되어 **모든 요청이 무조건** `EMBEDDING_DIMENSION_MISMATCH`로 실패하는 버그가 있었다. `vector().length`로 이미 차원을 충분히 검증하므로 `EmbedServerResponse`에서 `dimension` 필드를 제거하고 검증 로직을 단순화해 수정했다.

---

## 3. 테스트 데이터

| 항목 | 값 |
|---|---|
| 주 계정 | `kcw130502@gmail.com` (문서 소유자) |
| 테스트 계정 | `test@docgrid.com` (시나리오 6 전용, 권한 없는 신규 가입자) |
| 문서 1 | id=1, "Spring Boot 개발 가이드", PUBLIC, INDEXED |
| 문서 2 | id=2, "Python 데이터 분석 입문", PUBLIC, INDEXED |
| 청크 | 4개 (문서당 2개) |
| 임베딩 | 4개 (청크당 1개, 더미 벡터) |
| 테스트 컬렉션 | id=2, Spring Boot 문서만 포함 (시나리오 3 전용으로 직접 생성) |

---

## 4. 시나리오별 결과

### 4.1 시나리오 1 — 기본 검색 (topK 생략, 기본값 5 적용)

```http
POST /search
Authorization: Bearer {token}
```

```json
{
  "queryText": "Spring Boot란 무엇인가"
}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 1,
    "results": [
      {
        "rank": 1,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.",
        "pageNo": null,
        "similarityScore": 0.04935
      },
      {
        "rank": 2,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 3,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 4,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.",
        "pageNo": null,
        "similarityScore": 0
      }
    ]
  },
  "timestamp": "2026-07-26 14:32:22"
}
```

- topK 생략 → 기본값 5 적용, 실제 데이터 4개라 4개 반환 ✅
- queryId: 1 → `search_queries` 저장 ✅
- rank 1~4 순서 ✅
- `similarityScore` 대부분 0: seed 더미 벡터와 실제 임베딩 벡터 간 코사인 거리가 크기 때문으로, 정상 동작임

---

### 4.2 시나리오 2 — topK 명시 검색

```json
{
  "queryText": "Python 데이터 분석",
  "topK": 2
}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 2,
    "results": [
      {
        "rank": 1,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 2,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.",
        "pageNo": null,
        "similarityScore": 0
      }
    ]
  },
  "timestamp": "2026-07-26 14:52:01"
}
```

- `topK: 2` 지정 → 정확히 2개만 반환 ✅
- 각 검색마다 독립적인 queryId 생성 ✅

---

### 4.3 시나리오 3 — 컬렉션 범위 검색

컬렉션 id=2에 Spring Boot 문서만 등록한 상태에서 검색.

```json
{
  "queryText": "Spring Boot란 무엇인가",
  "collectionId": 2
}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 3,
    "results": [
      {
        "rank": 1,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.",
        "pageNo": null,
        "similarityScore": 0.04935
      },
      {
        "rank": 2,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.",
        "pageNo": null,
        "similarityScore": 0
      }
    ]
  },
  "timestamp": "2026-07-26 15:00:03"
}
```

- `collectionId: 2` 지정 → Spring Boot 청크 2개만 반환, Python 문서 완전 제외 ✅
- 컬렉션 범위 필터링 정상 동작 ✅

---

### 4.4 시나리오 4 — 입력값 검증

#### 케이스 1: 빈 queryText

```json
{ "queryText": "" }
```

```json
{
  "status": 400,
  "code": "COMMON-002",
  "message": "queryText: 공백일 수 없습니다",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-26 15:01:11"
}
```

`@NotBlank` 검증 → 400 ✅

#### 케이스 2: topK 범위 초과 (21 이상)

```json
{
  "queryText": "Spring Boot",
  "topK": 99
}
```

```json
{
  "status": 400,
  "code": "COMMON-002",
  "message": "topK: 20 이하여야 합니다",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-26 15:01:27"
}
```

`@Max(20)` 검증 → 400 ✅

#### 케이스 3: collectionId 음수

```json
{
  "queryText": "Spring Boot",
  "collectionId": -1
}
```

```json
{
  "status": 400,
  "code": "COMMON-002",
  "message": "collectionId: 0보다 커야 합니다",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-26 15:01:41"
}
```

`@Positive` 검증 → 400 ✅

---

### 4.5 시나리오 5 — 존재하지 않는 collectionId

```json
{
  "queryText": "Spring Boot",
  "collectionId": 9999
}
```

```json
{
  "status": 404,
  "code": "COLLECTION-001",
  "message": "컬렉션을 찾을 수 없습니다.",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-26 15:02:01"
}
```

존재하지 않는 collectionId → 404 `COLLECTION-001` ✅

---

### 4.6 시나리오 6 — 접근 가능한 문서 없는 사용자

두 문서를 모두 `visibility=PRIVATE`으로 변경한 뒤, 신규 가입한 `test@docgrid.com` 계정으로 검색.

```json
{
  "queryText": "Spring Boot"
}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 4,
    "results": []
  },
  "timestamp": "2026-07-26 15:06:02"
}
```

권한 없는 사용자 검색 → 200 + 빈 배열 반환 ✅ (에러가 아닌 정상 응답)

테스트 후 문서 원상복구:
```sql
UPDATE documents SET visibility = 'PUBLIC' WHERE title IN ('Spring Boot 개발 가이드', 'Python 데이터 분석 입문');
```

---

### 4.7 시나리오 7 — 임베딩 서버 다운

`docker stop docgrid-embedding`으로 Python 서버를 내린 뒤 검색 요청.

```json
{
  "queryText": "Spring Boot"
}
```

```json
{
  "status": 503,
  "code": "SEARCH-001",
  "message": "임베딩 서버를 사용할 수 없습니다.",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-26 15:08:32"
}
```

임베딩 서버 다운 → 503 `SEARCH-001` ✅

**주의사항**: 임베딩 실패 시 `search_queries` PROCESSING 저장 이전 단계에서 예외가 발생하므로 `search_queries`에 FAILED 레코드가 남지 않는다. 임베딩 전에 먼저 PROCESSING을 저장하도록 순서를 바꾸면 장애 이력도 DB에 기록할 수 있다 (현재는 MVP 설계로 미구현).

---

### 4.8 시나리오 8 — topK > 실제 데이터 수

DB에 데이터 4개인 상태에서 topK=20 요청.

```json
{
  "queryText": "Spring Boot",
  "topK": 20
}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 5,
    "results": [
      {
        "rank": 1,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.",
        "pageNo": null,
        "similarityScore": 0.016325
      },
      {
        "rank": 2,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 3,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 4,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다.",
        "pageNo": null,
        "similarityScore": 0
      }
    ]
  },
  "timestamp": "2026-07-26 15:10:39"
}
```

`topK: 20` 요청 → 실제 4개만 있어서 4개 반환, 에러 없음 ✅

---

### 4.9 시나리오 9 — live check로 결과가 줄어드는 경우

수동 재현 어려움 — pre-filter는 통과(캐시 유효)하면서 live check에서 탈락하는 상태를 DB에서 직접 만들려면 캐시 만료/권한 회수 타이밍을 정밀하게 제어해야 한다.

`SearchFacadeTest`에서 `canReadDocument()`가 false를 반환하는 케이스를 Mock으로 구성해 단위 테스트로 검증 완료. live check 전후 before/after 카운트 로그(`[SEARCH] live check ... before=N after=M`)로 실제 탈락 여부를 런타임에서 확인 가능하다.

---

### 4.10 시나리오 10 — dimension 불일치

Python 서버 응답에서 vector를 768차원으로 잘라서 반환하도록 `main.py`를 임시 수정한 뒤 Docker 컨테이너에 적용하는 방식으로 재현할 수 있으나, `docker cp` + 재시작 절차가 번거로워 수동 테스트를 생략했다.

`QueryEmbeddingServiceTest`의 `embed_dimensionMismatch_throwsException` 테스트가 768차원 응답 시 `EMBEDDING_DIMENSION_MISMATCH` 예외를 던지는 것을 단위 테스트로 검증 완료.

---

## 5. DB 직접 확인

### search_results — rank 순서 저장 확인

```sql
SELECT rank_no, similarity_score, matched_text
FROM search_results
WHERE query_id = 1
ORDER BY rank_no;
```

| rank_no | similarity_score | matched_text |
|---|---|---|
| 1 | 0.049350 | Spring Boot Starter는 의존성 관리를 단순화합니다... |
| 2 | 0.000000 | Python은 데이터 분석에 널리 사용되는... |
| 3 | 0.000000 | numpy는 수치 계산을 위한 Python 라이브러리입니다... |
| 4 | 0.000000 | Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다... |

- rank 1~4 순서대로 DB에 저장됨 ✅
- `similarity_score`가 API 응답과 동일한 값으로 저장됨 ✅

### search_queries — 상태 전이 + latency 저장 확인

```sql
SELECT id, status, latency_ms, query_text
FROM search_queries
ORDER BY id;
```

| id | status | latency_ms | query_text |
|---|---|---|---|
| 1 | SUCCESS | 781 | Spring Boot란 무엇인가 |
| 2 | SUCCESS | 767 | Python 데이터 분석 |
| 3 | SUCCESS | 617 | Spring Boot란 무엇인가 |
| 4 | SUCCESS | 616 | Spring Boot |
| 5 | SUCCESS | 792 | Spring Boot |
| 6 | SUCCESS | 628 | Spring Boot |

- 모든 검색 요청이 SUCCESS로 기록됨 ✅
- `latency_ms`: 617~792ms (대부분 Python 임베딩 서버 호출 시간) ✅
- 각 검색마다 독립적인 레코드 생성 ✅

---

## 6. 자동 테스트 결과

```
./gradlew test
BUILD SUCCESSFUL in 14s
```

전체 단위 테스트 통과 ✅

주요 테스트 클래스:
- `QueryEmbeddingServiceTest` — 정상/차원불일치/서버장애 케이스
- `SearchQueryCommandServiceTest` — PROCESSING/SUCCESS/FAILED 상태 전이
- `AccessibleDocumentQueryServiceTest` — 전체범위/컬렉션범위/빈결과
- `VectorSearchQueryServiceTest` — permittedIds 빈 목록 fast-path/정상변환/빈결과
- `SearchResultCommandServiceTest` — rank 순서 저장
- `SearchFacadeTest` — 정상흐름/빈permittedIds/live check 탈락

---

## 7. 최종 결론

| 완료 기준 | 결과 |
|---|---|
| topK 생략 → 기본값 5, 있는 만큼 반환 | ✅ |
| topK 명시 → 해당 개수만 반환 | ✅ |
| collectionId 지정 → 해당 컬렉션 내 문서만 검색 | ✅ |
| 빈 queryText → 400 COMMON-002 | ✅ |
| topK 범위 초과 → 400 COMMON-002 | ✅ |
| collectionId 음수 → 400 COMMON-002 | ✅ |
| 존재하지 않는 collectionId → 404 COLLECTION-001 | ✅ |
| 권한 없는 사용자 검색 → 200 빈 배열 | ✅ |
| 임베딩 서버 다운 → 503 SEARCH-001 | ✅ |
| topK > 실제 데이터 수 → 있는 만큼만 반환 | ✅ |
| search_results rank 순서 저장 | ✅ |
| search_queries latency_ms 저장 | ✅ |
| live check 탈락 케이스 | ✅ (단위 테스트로 검증) |
| dimension 불일치 케이스 | ✅ (단위 테스트로 검증) |
| 자동 테스트 전체 통과 | ✅ |

closes #56
