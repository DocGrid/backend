# #54 검색 블록 — 권한 pre-filter (F-SEARCH-04)

closes #54

## 배경

벡터 검색을 실행하기 전에 사용자가 읽을 수 있는 문서 ID 목록을 먼저 확보해야 한다.
이 목록을 pgvector의 `<->` 거리 연산에 `WHERE document_id IN (...)` 형태로 전달해
접근 불가 문서가 검색 결과에 노출되지 않도록 막는 것이 이 이슈의 목표다.

실제 `POST /search` API 조립과 벡터 쿼리 실행은 Issue 5에서 진행한다.
이 이슈는 "어떤 문서 ID를 검색 대상으로 쓸 것인가"를 결정하는 pre-filter 서비스 구현에 집중한다.

---

## 작업 내용

### 1. `DocumentRepository` — UNION 네이티브 쿼리 2종 추가

5가지 접근 경로를 UNION으로 합산해 한 번의 쿼리로 접근 가능한 문서 ID 전체를 반환한다.
공통 조건: `deleted_at IS NULL AND status = 'INDEXED'`

| 브랜치 | 테이블 조합 | 설명 |
|---|---|---|
| OWNER | `documents` | `owner_user_id = userId` |
| PUBLIC | `documents` | `visibility = 'PUBLIC'` |
| USER 캐시 | `documents` + `user_document_access_cache` | 유효한 읽기 캐시 존재 (`invalidated_at IS NULL`, 만료 미포함) |
| ROLE live (문서) | `documents` + `document_permissions` + `user_roles` | `target_type = 'ROLE'`, `can_read = true`, 만료 미포함 |
| DEPT live (문서) | `documents` + `document_permissions` + `users` | `target_type = 'DEPARTMENT'`, `can_read = true`, 만료 미포함 |
| ROLE live (컬렉션) | `documents` + `collection_documents` + `collection_permissions` + `user_roles` | 컬렉션 권한 → 문서, ROLE |
| DEPT live (컬렉션) | `documents` + `collection_documents` + `collection_permissions` + `users` | 컬렉션 권한 → 문서, DEPT |

#### `findReadableDocumentIds(userId)` — 전체 범위

위 7개 브랜치를 UNION으로 합산한 단일 쿼리.

#### `findReadableDocumentIdsInCollection(userId, collectionId)` — 컬렉션 범위

전체 UNION을 서브쿼리(`sub`)로 감싸고, `collection_documents`의 `collection_id = :collectionId` 조건으로 교집합을 구한다.

```sql
SELECT sub.id FROM ( ... UNION ... ) sub
WHERE sub.id IN (
    SELECT cd_filter.document_id FROM collection_documents cd_filter
    WHERE cd_filter.collection_id = :collectionId
)
```

`collection_documents`에 `idx_collection_documents_collection_id` 인덱스가 있으므로 IN 서브쿼리 성능은 안정적이다.

---

### 2. `AccessibleDocumentQueryService` (신규)

`domain/search/service/query/AccessibleDocumentQueryService.java`

```text
findReadableDocumentIds(userId, collectionId)
  ├─ collectionId == null → findReadableDocumentIds(userId)
  └─ collectionId != null → findReadableDocumentIdsInCollection(userId, collectionId)
```

- `@Transactional(readOnly = true)` — 읽기 전용
- 빈 목록 반환 시 호출 측(Issue 5 SearchFacade)에서 벡터 검색을 건너뛸 수 있도록 그대로 반환
- 현재 이슈 범위에서는 빈 목록 fast-path 처리를 서비스 내부에서 수행하지 않는다 (호출 측 책임)

---

## 에러 케이스 정리

| 상황 | 처리 방식 |
|------|-----------|
| 접근 가능한 문서 없음 | 빈 `List<Long>` 반환. 호출 측에서 벡터 검색 skip |
| INDEXED 상태가 아닌 문서 | UNION 쿼리 조건 `status = 'INDEXED'`로 자동 제외 |
| soft delete된 문서 | `deleted_at IS NULL` 조건으로 자동 제외 |
| 만료된 권한 | `expires_at IS NULL OR expires_at > NOW()` 조건으로 자동 제외 |
| 무효화된 캐시 | `invalidated_at IS NULL` 조건으로 자동 제외 |

---

## 설계 결정

**UNION 방식 선택 이유**

단건 boolean 체크(기존 `existsRoleReadPermission` 등)를 반복 호출하는 방식은 검색 대상 문서 수가 증가할수록 N번의 쿼리가 발생한다.
UNION 방식은 접근 경로별로 DB가 병렬 처리할 수 있고, 결과는 Set의 합집합으로 중복 없이 반환된다.

**`collectionId` nullable 처리**

컬렉션 범위 검색은 선택적 기능이다. null이면 전체 범위, 값이 있으면 컬렉션 범위로 자연스럽게 분기한다.
서비스 메서드 시그니처를 `(userId, collectionId)` 단일 진입점으로 유지해 Issue 5 조립 시 호출 코드가 단순해진다.

**외부 서브쿼리 vs 각 브랜치 개별 필터**

컬렉션 범위 쿼리에서 "UNION 전체를 서브쿼리로 감싸고 외부에서 컬렉션 필터 적용" 방식을 선택했다.
각 브랜치마다 `AND d.id IN (SELECT ...)` 조건을 추가하는 방식과 성능 차이는 PostgreSQL 플래너 의존적이며,
현재는 가독성과 중복 제거 측면에서 서브쿼리 감싸기가 더 유리하다고 판단했다.
