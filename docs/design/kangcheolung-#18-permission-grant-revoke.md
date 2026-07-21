# Issue #18 권한 부여·회수 및 USER 캐시 설계

## 1. 목적

컬렉션과 문서 단위로 USER/ROLE/DEPARTMENT 대상 권한을 부여하고 회수한다. USER 대상 권한은 즉시 접근 캐시에 반영한다.

```text
컬렉션 권한 부여 / 회수
문서 예외 권한 부여 / 회수
USER 캐시 즉시 갱신 / 무효화
```

```text
브랜치명: feature/18
```

---

## 2. 핵심 용어

### 기본 권한 vs 예외 권한

```text
기본 권한 단위: collection_permissions
→ 컬렉션에 권한을 부여하면 컬렉션 소속 모든 문서에 적용
→ 대부분의 권한은 이 방식으로 관리

예외 권한: document_permissions
→ 특정 문서 하나에만 별도 권한 부여
→ 최소한으로만 사용
```

### 권한 대상 타입 (PermissionTargetType)

```text
USER       — 특정 사용자 개인에게 부여
ROLE       — 특정 역할을 가진 모든 사용자에게 부여 (live)
DEPARTMENT — 특정 부서 소속 모든 사용자에게 부여 (live)
```

ROLE/DEPARTMENT는 캐시에 저장하지 않고 매 요청마다 live 조회한다.

### 권한 종류 (PermissionType)

```text
READ  → canRead=true
WRITE → canRead=true, canWrite=true
ADMIN → canRead=true, canWrite=true, canAdmin=true
```

WRITE는 READ를 포함하고, ADMIN은 WRITE와 READ를 모두 포함한다.

### UserDocumentAccessCache

USER 대상 권한만 저장하는 검색 pre-filter 가속 캐시다.

```text
저장 대상: OWNER, DIRECT_DOCUMENT_PERMISSION, DIRECT_COLLECTION_PERMISSION
저장 안 함: ROLE, DEPARTMENT (live 조회로 처리)

source_type / source_id 로 어떤 권한에서 파생된 캐시인지 추적
invalidated_at IS NULL → 유효한 캐시
invalidated_at 설정 → 무효화된 캐시 (레코드는 DB에 남음)
```

이 캐시는 권한의 source of truth가 아니다. 최종 접근 허용 여부는 반드시 live check를 거쳐야 한다.

---

## 3. API 계약

### 컬렉션 권한 부여

```http
POST /permissions/collections/{collectionId}
Authorization: Bearer {token}
Content-Type: application/json
```

요청 필드:

| 필드 | 필수 | 설명 |
|---|---|---|
| `targetType` | 필수 | `USER` / `ROLE` / `DEPARTMENT` |
| `userId` | 조건부 | `targetType=USER`일 때만 입력 |
| `roleId` | 조건부 | `targetType=ROLE`일 때만 입력 |
| `departmentId` | 조건부 | `targetType=DEPARTMENT`일 때만 입력 |
| `permissionType` | 필수 | `READ` / `WRITE` / `ADMIN` |
| `expiresAt` | 선택 | 권한 만료 시각 (null이면 만료 없음) |

targetType에 맞지 않는 ID 필드를 함께 입력하면 `400 Bad Request`를 반환한다.

성공 응답 `201 Created`:

```json
{
  "id": 7,
  "collectionId": 3,
  "targetType": "USER",
  "userId": 2,
  "permissionType": "READ",
  "canRead": true,
  "canWrite": false,
  "canAdmin": false,
  "grantedAt": "2025-07-01T10:00:00"
}
```

### 컬렉션 권한 회수

```http
DELETE /permissions/collections/{collectionId}/{permissionId}
Authorization: Bearer {token}
```

성공 응답 `204 No Content`

### 문서 예외 권한 부여

```http
POST /permissions/documents/{documentId}
Authorization: Bearer {token}
Content-Type: application/json
```

요청 필드와 구조는 컬렉션 권한 부여와 동일하다.

성공 응답 `201 Created`

### 문서 예외 권한 회수

```http
DELETE /permissions/documents/{documentId}/{permissionId}
Authorization: Bearer {token}
```

성공 응답 `204 No Content`

---

## 4. 구현 구조

```text
Controller
- PermissionController
  - POST /permissions/collections/{collectionId}
  - DELETE /permissions/collections/{collectionId}/{permissionId}
  - POST /permissions/documents/{documentId}
  - DELETE /permissions/documents/{documentId}/{permissionId}

Service (Command)
- CollectionPermissionCommandService
  - grantPermission(collectionId, grantorId, request)
  - revokePermission(collectionId, permissionId, revokerId)
- DocumentPermissionCommandService
  - grantPermission(documentId, grantorId, request)
  - revokePermission(documentId, permissionId, revokerId)
- UserDocumentAccessCacheService
  - grantUserPermission(...)      — 문서 단건 권한 부여 시 캐시 단건 갱신
  - revokeUserPermission(...)     — 문서 단건 권한 회수 시 캐시 단건 무효화
  - bulkGrantUserPermission(...)  — 컬렉션 권한 부여 시 캐시 일괄 갱신
  - bulkRevokeBySource(...)       — 컬렉션 권한 회수 시 캐시 일괄 무효화

Repository
- CollectionPermissionRepository
- DocumentPermissionRepository
- UserDocumentAccessCacheRepository
  - bulkUpdateBySource(...)
  - bulkInvalidateBySource(...)
```

---

## 5. 처리 흐름

### 컬렉션 권한 부여

```text
컬렉션 존재 확인
        ↓
요청자가 컬렉션 ADMIN 권한을 가지고 있는지 확인
        ↓
targetType과 ID 필드 조합 유효성 검사
        ↓
대상 User/Role/Department 존재 확인
        ↓
permissionType → canRead/canWrite/canAdmin 변환
        ↓
CollectionPermission 저장
        ↓
targetType = USER인 경우에만
  → 컬렉션 소속 문서 목록 조회
  → 기존 캐시 일괄 UPDATE
  → 캐시 없는 문서만 배치 INSERT
        ↓
201 Created 반환
```

### 컬렉션 권한 회수

```text
permissionId로 권한 조회
        ↓
collectionId와 권한의 컬렉션 ID 일치 확인
        ↓
요청자가 컬렉션 ADMIN 권한을 가지고 있는지 확인
        ↓
targetType = USER인 경우에만
  → 해당 권한에서 파생된 캐시 전체 일괄 무효화 (invalidated_at 설정)
        ↓
권한 레코드 삭제
        ↓
204 No Content 반환
```

### 문서 직접 권한 부여

```text
문서 존재 확인
        ↓
요청자가 문서 ADMIN 권한을 가지고 있는지 확인
        ↓
targetType과 ID 필드 조합 유효성 검사
        ↓
대상 User/Role/Department 존재 확인
        ↓
DocumentPermission 저장
        ↓
targetType = USER인 경우에만
  → 해당 문서에 대한 캐시 단건 저장 또는 갱신
        ↓
201 Created 반환
```

---

## 6. 캐시 갱신 전략

### 컬렉션 권한 부여 시 N+1 방지

컬렉션에 문서가 많을 경우 개별 INSERT보다 배치 처리를 사용한다.

```text
1. bulkUpdateBySource → 이미 캐시된 행 일괄 UPDATE
2. 캐시 없는 문서 ID 목록 조회
3. 새 캐시 목록 생성 → saveAll 배치 INSERT
```

### 권한 회수 시 soft invalidation

캐시 레코드를 삭제하지 않고 `invalidated_at`에 현재 시각을 기록한다.

```text
삭제 안 함 → 이력 보존
invalidated_at IS NULL → 유효
invalidated_at 있음 → 무효화됨
```

---

## 7. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 컬렉션 없음 | 404 | `COLLECTION_NOT_FOUND` |
| 문서 없음 | 404 | `DOCUMENT_NOT_FOUND` |
| 권한 없음 | 404 | `COLLECTION_PERMISSION_NOT_FOUND` / `DOCUMENT_PERMISSION_NOT_FOUND` |
| 사용자 없음 | 404 | `USER_NOT_FOUND` |
| 역할 없음 | 404 | `ROLE_NOT_FOUND` |
| 부서 없음 | 404 | `DEPARTMENT_NOT_FOUND` |
| ADMIN 권한 없음 | 403 | `PERMISSION_DENIED` |
| targetType 조합 오류 | 400 | `INVALID_TARGET_TYPE` |

---

## 8. 완료 기준

- 컬렉션/문서에 USER/ROLE/DEPARTMENT 단위로 READ/WRITE/ADMIN 권한을 부여할 수 있다.
- targetType에 맞지 않는 ID 필드를 입력하면 400을 반환한다.
- 컬렉션/문서 ADMIN 권한이 없는 사용자가 권한을 부여하려 하면 403을 반환한다.
- USER 대상 컬렉션 권한 부여 시 컬렉션 소속 문서 전체에 캐시가 즉시 갱신된다.
- USER 대상 문서 권한 부여 시 해당 문서 캐시가 즉시 갱신된다.
- 권한 회수 시 관련 캐시가 즉시 무효화된다 (레코드는 DB에 유지).
- ROLE/DEPARTMENT 권한은 캐시에 저장하지 않는다.
