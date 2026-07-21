# Issue #29 컬렉션 관리 API 설계

## 1. 목적

컬렉션 목록 조회, 컬렉션 삭제, 컬렉션에서 문서 제거 API를 구현한다. 삭제/제거 시 관련 캐시를 즉시 무효화한다.

```text
GET    /collections                              — 내 컬렉션 목록
DELETE /collections/{collectionId}               — 컬렉션 삭제 (soft delete)
DELETE /collections/{collectionId}/documents/{documentId} — 컬렉션에서 문서 제거
```

```text
브랜치명: feature/29
```

---

## 2. 핵심 개념

### Soft Delete

컬렉션 삭제는 DB에서 레코드를 제거하지 않는다. `status = DELETED`, `deleted_at` 시각을 기록하는 방식이다.

```text
삭제 전: status = ACTIVE, deleted_at = null
삭제 후: status = DELETED, deleted_at = 현재 시각
```

목록 조회는 `status = ACTIVE`인 컬렉션만 반환한다.

### 소유자 제한

삭제와 문서 제거는 컬렉션 ADMIN 권한이 아닌 **소유자(owner)만** 가능하다.

```text
collection.owner_user_id = 요청 userId → 허용
다른 사람 → 403
```

ADMIN 권한을 가진 외부 사용자가 컬렉션 자체를 삭제하거나 구성 문서를 임의로 제거하는 것을 막기 위해 소유자로 제한한다.

### 캐시 무효화 범위 차이

삭제와 문서 제거는 캐시 무효화 범위가 다르다.

```text
컬렉션 삭제
→ 컬렉션의 모든 권한(collection_permissions)에서 파생된 캐시 전체 무효화
→ 컬렉션 소속 모든 문서 × 모든 USER 대상 권한 캐시

컬렉션에서 문서 제거
→ 제거된 그 문서에 대한 캐시만 무효화
→ 컬렉션 소속 나머지 문서의 캐시는 그대로 유지
```

---

## 3. API 계약

### 내 컬렉션 목록 조회

```http
GET /collections
Authorization: Bearer {token}
```

성공 응답 `200 OK`:

```json
[
  {
    "id": 3,
    "name": "설계 문서",
    "description": "설계 관련 문서 모음",
    "visibility": "PRIVATE",
    "status": "ACTIVE",
    "ownerId": 1
  }
]
```

로그인한 사용자가 소유한 `ACTIVE` 상태의 컬렉션만 반환한다. `DELETED` 컬렉션은 포함하지 않는다.

### 컬렉션 삭제

```http
DELETE /collections/{collectionId}
Authorization: Bearer {token}
```

성공 응답 `204 No Content`

### 컬렉션에서 문서 제거

```http
DELETE /collections/{collectionId}/documents/{documentId}
Authorization: Bearer {token}
```

성공 응답 `204 No Content`

---

## 4. 구현 구조

```text
Controller
- CollectionController
  - GET /collections
  - DELETE /collections/{collectionId}
  - DELETE /collections/{collectionId}/documents/{documentId}

Service (Query)
- CollectionQueryService
  - getMyCollections(userId)

Service (Command)
- CollectionCommandService
  - deleteCollection(collectionId, userId)
  - removeDocument(collectionId, documentId, userId)

Repository
- CollectionRepository
  - findAllByOwnerIdAndStatus(ownerId, status)
- CollectionDocumentRepository
  - findByCollectionIdAndDocumentId(collectionId, documentId)
- CollectionPermissionRepository
  - findAllByCollectionId(collectionId)
- UserDocumentAccessCacheRepository
  - bulkInvalidateBySource(sourceType, sourceId)
  - bulkInvalidateBySourceIdsAndDocument(sourceType, sourceIds, documentId)

Service
- UserDocumentAccessCacheService
  - bulkRevokeBySource(sourceType, sourceId)            — 권한 단위 전체 무효화
  - bulkRevokeBySourcesForDocument(sourceType, sourceIds, documentId) — 특정 문서만 무효화
```

---

## 5. 처리 흐름

### 컬렉션 삭제

```text
컬렉션 존재 확인
        ↓
소유자 확인 (owner_user_id = userId) → 아니면 403
        ↓
컬렉션의 모든 권한(collection_permissions) 조회
        ↓
USER 대상 권한 각각
  → bulkRevokeBySource(DIRECT_COLLECTION_PERMISSION, permissionId)
  → invalidated_at 일괄 SET
        ↓
권한 레코드 전체 삭제
        ↓
collection.status = DELETED, deleted_at = now (soft delete)
        ↓
204 No Content
```

### 컬렉션에서 문서 제거

```text
컬렉션 존재 확인
        ↓
소유자 확인 → 아니면 403
        ↓
CollectionDocument 존재 확인 → 없으면 404
        ↓
컬렉션의 USER 대상 권한 ID 목록 조회
        ↓
bulkRevokeBySourcesForDocument(DIRECT_COLLECTION_PERMISSION, userPermissionIds, documentId)
  → 단일 UPDATE 쿼리로 해당 문서의 캐시만 무효화
        ↓
CollectionDocument 삭제
        ↓
204 No Content
```

---

## 6. 캐시 무효화 쿼리

### bulkInvalidateBySource (컬렉션 삭제 시)

권한 하나에서 파생된 모든 문서 캐시를 무효화한다.

```sql
UPDATE user_document_access_cache
SET invalidated_at = CURRENT_TIMESTAMP
WHERE source_type = :sourceType
  AND source_id = :sourceId
  AND invalidated_at IS NULL
```

### bulkInvalidateBySourceIdsAndDocument (문서 제거 시)

여러 권한에서 파생됐지만 특정 문서에 해당하는 캐시만 무효화한다.

```sql
UPDATE user_document_access_cache
SET invalidated_at = CURRENT_TIMESTAMP
WHERE source_type = :sourceType
  AND source_id IN :sourceIds
  AND document_id = :documentId
  AND invalidated_at IS NULL
```

문서 제거 시 나머지 문서의 캐시는 건드리지 않는다.

---

## 7. DB 변화 예시

컬렉션 3번에 문서 5, 6, 7번이 있고 유저 B에게 READ 권한이 있을 때:

**문서 5번 제거 후**

```text
collection_documents: 문서 5번 행 삭제, 6·7번 행 유지
user_document_access_cache: 문서 5번 행만 invalidated_at 설정
                             문서 6·7번 행 유지
```

**컬렉션 삭제 후**

```text
collections: status = DELETED, deleted_at = now
collection_permissions: 전체 삭제
user_document_access_cache: 컬렉션 권한에서 파생된 모든 행 invalidated_at 설정
```

---

## 8. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 컬렉션 없음 | 404 | `COLLECTION_NOT_FOUND` |
| 소유자 아님 | 403 | `PERMISSION_DENIED` |
| 컬렉션에 해당 문서 없음 | 404 | `COLLECTION_DOCUMENT_NOT_FOUND` |

---

## 9. 완료 기준

- 소유한 `ACTIVE` 상태 컬렉션 목록만 반환한다.
- 컬렉션 삭제는 소유자만 가능하다.
- 컬렉션 삭제 시 DB에서 제거하지 않고 `status = DELETED`, `deleted_at`을 설정한다.
- 컬렉션 삭제 시 소속 모든 권한에서 파생된 USER 캐시가 일괄 무효화된다.
- 문서 제거는 소유자만 가능하다.
- 문서 제거 시 해당 문서에 대한 캐시만 무효화되고 나머지 문서 캐시는 유지된다.
- 컬렉션에 없는 문서를 제거하려 하면 404를 반환한다.
