# Issue #24 내 문서 권한 확인 API 설계

## 1. 목적

현재 로그인한 사용자가 특정 문서에 대해 어떤 권한을 가지고 있는지, 그 권한이 어떤 경로로 부여됐는지 한 번에 확인한다.

```text
canRead / canWrite / canAdmin 세 가지를 동시에 판단
권한이 어떤 경로(sources)로 부여됐는지도 반환
```

```text
브랜치명: feature/24
```

---

## 2. boolean 메서드와의 차이

`canReadDocument` 같은 boolean 메서드는 하나의 권한 여부만 단락 평가로 빠르게 판단한다.

```text
canReadDocument → 읽기 권한만 확인, 첫 번째 true에서 즉시 반환
canWriteDocument → 쓰기 권한만 확인, 첫 번째 true에서 즉시 반환
```

이 API는 세 가지 권한을 동시에, 모든 경로를 탐색해서 반환해야 한다.

```text
checkDocumentPermission
→ 모든 5단계를 끝까지 탐색 (단락 평가 없음)
→ 권한을 준 경로(sources)를 모두 수집
→ canRead / canWrite / canAdmin 동시 반환
```

단락 평가를 하지 않는 이유: 단계 A에서 READ를 얻었더라도 단계 B에서 WRITE를 얻을 수 있기 때문이다.

---

## 3. PermissionSourceType

권한 경로를 나타내는 enum이다.

```text
OWNER      — 문서 소유자
PUBLIC     — 문서가 PUBLIC으로 공개됨
USER_CACHE — user_document_access_cache에 직접 부여된 USER 권한
ROLE       — ROLE 기반 권한 (document_permissions 또는 collection_permissions)
DEPARTMENT — DEPARTMENT 기반 권한 (document_permissions 또는 collection_permissions)
```

---

## 4. API 계약

```http
GET /permissions/documents/{documentId}/me
Authorization: Bearer {token}
```

성공 응답 `200 OK`:

```json
{
  "documentId": 5,
  "canRead": true,
  "canWrite": false,
  "canAdmin": false,
  "sources": ["PUBLIC", "USER_CACHE"]
}
```

권한이 전혀 없는 경우:

```json
{
  "documentId": 5,
  "canRead": false,
  "canWrite": false,
  "canAdmin": false,
  "sources": []
}
```

소유자인 경우:

```json
{
  "documentId": 5,
  "canRead": true,
  "canWrite": true,
  "canAdmin": true,
  "sources": ["OWNER"]
}
```

---

## 5. 판단 로직

### 1단계: OWNER — 즉시 반환

소유자는 모든 권한을 가지므로 유일하게 단락 평가를 허용한다.

```text
document.owner_user_id = userId
→ sources = [OWNER]
→ canRead=true, canWrite=true, canAdmin=true
→ 즉시 반환 (이후 단계 실행 안 함)
```

### 2단계: PUBLIC — 단락 없이 계속

```text
document.visibility = PUBLIC
→ sources에 PUBLIC 추가
→ canRead = true
→ 계속 (write/admin은 PUBLIC으로 얻을 수 없으므로 이후 단계 탐색)
```

### 3단계: USER 캐시

```text
existsValidReadCache → canRead = true
existsValidWriteCache → canWrite = true
existsValidAdminCache → canAdmin = true
셋 중 하나라도 있으면 → sources에 USER_CACHE 추가
```

### 4단계: ROLE live

```text
문서 직접 ROLE 권한 OR 컬렉션 경유 ROLE 권한
→ read / write / admin 각각 확인
→ 하나라도 있으면 sources에 ROLE 추가
```

### 5단계: DEPARTMENT live

```text
문서 직접 DEPT 권한 OR 컬렉션 경유 DEPT 권한
→ read / write / admin 각각 확인
→ 하나라도 있으면 sources에 DEPARTMENT 추가
```

---

## 6. 구현 구조

```text
Controller
- PermissionController
  - GET /permissions/documents/{documentId}/me

Service (Query)
- PermissionQueryService
  - checkDocumentPermission(userId, documentId)

DTO
- DocumentPermissionSummaryResponse (record)
  - documentId
  - canRead
  - canWrite
  - canAdmin
  - sources: List<PermissionSourceType>

Enum
- PermissionSourceType
  - OWNER / PUBLIC / USER_CACHE / ROLE / DEPARTMENT
```

---

## 7. 처리 흐름

```text
문서 존재 확인
        ↓
OWNER 확인 → 맞으면 즉시 반환
        ↓
PUBLIC 확인 → canRead = true, sources에 추가
        ↓
USER 캐시 3번 조회 → canRead/Write/Admin 갱신, sources에 추가
        ↓
ROLE 6번 조회 (문서+컬렉션 × read/write/admin) → 갱신, sources에 추가
        ↓
DEPT 6번 조회 (문서+컬렉션 × read/write/admin) → 갱신, sources에 추가
        ↓
DocumentPermissionSummaryResponse 반환
```

---

## 8. 오류 응답

| 상황 | HTTP | 오류 코드 |
|---|---:|---|
| 문서 없음 | 404 | `DOCUMENT_NOT_FOUND` |

권한이 없어도 오류가 아니다. `sources: []`, 모두 false로 반환한다.

---

## 9. 완료 기준

- 소유자는 `sources: ["OWNER"]`, 모두 true를 반환한다.
- PUBLIC 문서는 READ만 sources에 PUBLIC이 포함된다.
- USER 캐시로 부여된 권한은 `sources: ["USER_CACHE"]`가 포함된다.
- ROLE/DEPT 권한은 sources에 각각 ROLE / DEPARTMENT가 포함된다.
- 여러 경로에서 권한을 받은 경우 sources에 모두 포함된다.
- 권한이 없으면 `sources: []`, 모두 false를 반환한다.
- 문서가 없으면 404를 반환한다.
