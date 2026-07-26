# #54 검색 블록 — 권한 pre-filter (F-SEARCH-04)

closes #54

---

## 배경

벡터 검색을 실행하기 전에 사용자가 읽을 수 있는 `document_id` 목록을 먼저 확보해야 한다. 이 목록을 pgvector의 `<=>` 연산에 `WHERE document_id IN (...)` 형태로 전달해 접근 불가 문서가 검색 결과에 노출되지 않도록 막는 것이 이 이슈의 목표다.

기존 `PermissionQueryService.canReadDocument()`는 단건 boolean 검증이라 목록 반환 용도로 재사용 불가 — 별도 쿼리를 새로 작성.

실제 `POST /search` API 조립과 벡터 쿼리 실행은 Issue 5(#56)에서 진행한다.

---

## 1. DocumentRepository — UNION 쿼리 2종

5가지 접근 경로를 7개 SQL 브랜치로 표현해 UNION으로 합산, 한 번의 쿼리로 접근 가능한 문서 ID 전체를 반환한다.

### 7-branch UNION 구조

| 번호 | 접근 경로 | 판단 근거 |
|---|---|---|
| 1 | OWNER | `documents.owner_user_id` |
| 2 | PUBLIC | `documents.visibility = 'PUBLIC'` |
| 3 | USER 캐시 | `user_document_access_cache` (USER-type 권한만 캐시됨) |
| 4 | ROLE (문서 단위) | `document_permissions` + `user_roles` |
| 5 | DEPT (문서 단위) | `document_permissions` + `users.department_id` |
| 6 | ROLE (컬렉션 단위) | `collection_permissions` + `user_roles` |
| 7 | DEPT (컬렉션 단위) | `collection_permissions` + `users.department_id` |

모든 브랜치 공통 조건: `d.deleted_at IS NULL AND d.status = 'INDEXED'`  
삭제되지 않았고 A담당자 파이프라인(업로드→청킹→임베딩)이 완료된 문서만 대상. INDEXED가 아닌 문서는 embeddings 자체가 불완전할 수 있어 pre-filter 단계에서부터 제외.

```sql
-- OWNER
SELECT d.id FROM documents d
WHERE d.owner_user_id = :userId AND d.deleted_at IS NULL AND d.status = 'INDEXED'

UNION

-- PUBLIC
SELECT d.id FROM documents d
WHERE d.visibility = 'PUBLIC' AND d.deleted_at IS NULL AND d.status = 'INDEXED'

UNION

-- USER 캐시
SELECT d.id FROM documents d
  JOIN user_document_access_cache c ON c.document_id = d.id
WHERE c.user_id = :userId AND c.can_read = true AND c.invalidated_at IS NULL
  AND (c.expires_at IS NULL OR c.expires_at > NOW())
  AND d.deleted_at IS NULL AND d.status = 'INDEXED'

UNION

-- ... ROLE/DEPT × 문서/컬렉션 4개 브랜치 동일 패턴 반복 ...
```

### `findReadableDocumentIds(userId)` — 전체 범위

위 7개 브랜치를 UNION으로 합산한 단일 쿼리.

### `findReadableDocumentIdsInCollection(userId, collectionId)` — 컬렉션 범위

전체 UNION을 서브쿼리(`sub`)로 감싸고, 바깥에서 `collection_documents`로 한 번 더 필터링.

```sql
SELECT sub.id FROM ( ...7-branch UNION... ) sub
WHERE sub.id IN (
    SELECT cd_filter.document_id FROM collection_documents cd_filter
    WHERE cd_filter.collection_id = :collectionId
)
```

각 브랜치 안에서 개별적으로 필터링하는 대신 전체를 감싸는 방식을 택해 코드 중복을 줄임. (`collection_documents`에 `idx_collection_documents_collection_id` 인덱스가 있어 IN 서브쿼리 성능 안정적)

**이 Repository 자체는 원래 A담당자 소유**(문서 조회/락 관련)지만, pre-filter 쿼리는 `documents` 테이블 대상 조회라서 새 Repository를 만들지 않고 기존 것에 추가하는 방식을 택함.

---

## 2. AccessibleDocumentQueryService

**한 줄 요약**: `collectionId` 유무에 따라 전체/컬렉션 범위 pre-filter 쿼리 중 하나를 골라 호출하는 얇은 라우팅 서비스.

```java
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class AccessibleDocumentQueryService {

    private final DocumentRepository documentRepository;

    public List<Long> findReadableDocumentIds(Long userId, Long collectionId) {
        if (collectionId != null) {
            return documentRepository.findReadableDocumentIdsInCollection(userId, collectionId);
        }
        return documentRepository.findReadableDocumentIds(userId);
    }
}
```

실제 권한 판단 로직은 전부 Repository의 SQL에 있고, 이 서비스는 "collectionId 유무로 어느 쿼리를 쓸지 고르는" 얇은 라우팅 역할만 함. `readOnly = true`로 조회 전용임을 명시(더티체킹 등 불필요한 오버헤드 생략).

빈 목록 반환 시 호출 측(Issue 5 SearchFacade)에서 벡터 검색을 건너뛰도록 그대로 반환. 빈 목록 fast-path 처리는 서비스 내부가 아닌 호출 측 책임.

---

## 에러 케이스 정리

| 상황 | 처리 방식 |
|---|---|
| 접근 가능한 문서 없음 | 빈 `List<Long>` 반환. 호출 측에서 벡터 검색 skip |
| INDEXED 상태가 아닌 문서 | `status = 'INDEXED'` 조건으로 자동 제외 |
| soft delete된 문서 | `deleted_at IS NULL` 조건으로 자동 제외 |
| 만료된 권한 | `expires_at IS NULL OR expires_at > NOW()` 조건으로 자동 제외 |
| 무효화된 캐시 | `invalidated_at IS NULL` 조건으로 자동 제외 |

---

## 설계 결정 요약

**UNION 방식 선택 이유**

단건 boolean 체크(`existsRoleReadPermission` 등)를 반복 호출하는 방식은 검색 대상 문서 수가 증가할수록 N번의 쿼리가 발생한다. UNION 방식은 접근 경로별 조건을 단일 SQL로 합쳐 한 번의 쿼리로 처리하며, UNION의 중복 제거로 동일 문서 ID가 여러 경로에서 매칭돼도 한 번만 반환된다.

**`collectionId` nullable 처리**

컬렉션 범위 검색은 선택적 기능. null이면 전체 범위, 값이 있으면 컬렉션 범위로 자연스럽게 분기. 서비스 메서드 시그니처를 `(userId, collectionId)` 단일 진입점으로 유지해 Issue 5 조립 시 호출 코드가 단순해진다.

**외부 서브쿼리 vs 각 브랜치 개별 필터**

컬렉션 범위 쿼리에서 "UNION 전체를 서브쿼리로 감싸고 외부에서 컬렉션 필터 적용" 방식 선택. 각 브랜치마다 `AND d.id IN (SELECT ...)` 조건을 추가하는 방식과 성능 차이는 PostgreSQL 플래너 의존적이며, 현재는 가독성과 코드 중복 제거 측면에서 서브쿼리 감싸기가 더 유리하다고 판단.

**문서 규모 확장 시 재검토 포인트**

- `findReadableDocumentIdsInCollection`의 서브쿼리 방식은 문서 규모가 커지면 전체 UNION 계산 후 필터링하는 구조라 비효율적일 수 있음.
- `WHERE document_id IN (:permittedIds)` 파라미터가 수천 건 이상이 되면 성능 영향 가능성. 현재 MVP 규모에서는 문제 없음.
