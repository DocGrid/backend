# 권한 시스템 Swagger 수동 테스트 결과

## 1. 테스트 목적

이슈 #16 · #18 · #21 · #24 · #29에서 구현한 권한 시스템 전체가 다음 계약을 지키는지 Swagger에서 확인했다.

- 문서 소유자는 별도 권한 부여 없이 read · write · admin 전부 보유한다.
- PUBLIC 문서는 별도 권한이 없는 사용자에게 canRead만 true이고 canWrite · canAdmin은 false다. USER·ROLE·DEPT 권한이 추가로 있으면 해당 경로도 sources에 수집되고 canWrite·canAdmin도 true가 될 수 있다.
- USER 직접 권한을 부여하면 user_document_access_cache에 즉시 캐시가 생성된다.
- 컬렉션에 ROLE 권한을 부여하면 해당 역할 보유자가 소속 문서에 접근할 수 있다.
- USER 직접 권한을 회수해도 ROLE 경로가 남아 있으면 접근이 유지된다.
- 컬렉션에서 문서를 제거하면 ROLE 경로가 끊겨 접근이 차단된다. (DEPARTMENT 시나리오는 이번 테스트 범위에서 제외)
- 컬렉션 삭제는 soft delete(status=DELETED)로 처리되고 목록에서 제외된다.
- sources 필드에 권한이 부여된 경로가 모두 수집된다.

상세 설계는 아래 문서를 참고한다.
- [`docs/chelung-#16-collection-crud.md`](../chelung-#16-collection-crud.md)
- [`docs/chelung-#18-permission-grant-revoke.md`](../chelung-#18-permission-grant-revoke.md)
- [`docs/chelung-#21-permission-query-service.md`](../chelung-#21-permission-query-service.md)
- [`docs/chelung-#24-document-permission-check.md`](../chelung-#24-document-permission-check.md)
- [`docs/chelung-#29-collection-management.md`](../chelung-#29-collection-management.md)

---

## 2. 테스트 환경과 제약

- Spring Boot local profile, PostgreSQL 로컬
- Swagger UI 두 탭을 동시에 열어 A · B 계정을 각각 인증
- 자동 테스트(`./gradlew test`)는 별도 이슈로 분리 예정

---

## 3. 테스트 데이터

| 항목 | 값 |
|---|---|
| A 계정 | userId=2 (문서 소유자 · 권한 부여자) |
| B 계정 | userId=3 (test1), role_id=4, department_id=4 |
| 문서1 | id=1, visibility=PRIVATE, owner=A |
| 문서2 | id=2, visibility=PUBLIC, owner=A |
| 컬렉션1 | id=1, owner=A, 문서1 포함 |

---

## 4. 시나리오별 결과

### 4.1 OWNER 경로 확인

A(소유자)가 자신의 문서에 `/me`를 호출한다.

```http
GET /permissions/documents/1/me
Authorization: Bearer {A token}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": true,
    "canWrite": true,
    "canAdmin": true,
    "sources": ["OWNER"]
  },
  "timestamp": "2026-07-17 17:08:02"
}
```

서버 로그:
```
[PERM] checkDoc owner doc=1 user=2 elapsed=7.94ms
```

소유자는 1단계에서 즉시 반환되고 이후 단계는 실행되지 않는다.

---

### 4.2 PUBLIC 경로 확인

B가 PUBLIC 문서에 `/me`를 호출한다.

```http
GET /permissions/documents/2/me
Authorization: Bearer {B token}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 2,
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "sources": ["PUBLIC"]
  },
  "timestamp": "2026-07-17 17:08:23"
}
```

서버 로그:
```
[PERM] checkDoc doc=2 user=3 canRead=true canWrite=false canAdmin=false sources=[PUBLIC] elapsed=33.446208ms
```

PUBLIC은 canRead만 true이고 canWrite · canAdmin은 false다.

---

### 4.3 USER 직접 권한 부여 → USER_CACHE 경로 확인

A가 B에게 문서1 READ 권한을 직접 부여한다.

```http
POST /permissions/documents/1
Authorization: Bearer {A token}
```

```json
{
  "targetType": "USER",
  "userId": 3,
  "roleId": null,
  "departmentId": null,
  "permissionType": "READ",
  "expiresAt": null
}
```

```json
{
  "success": true,
  "status": 201,
  "data": {
    "permissionId": 2,
    "documentId": 1,
    "targetType": "USER",
    "userId": 3,
    "roleId": null,
    "departmentId": null,
    "permissionType": "READ",
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "grantedBy": 2,
    "grantedAt": "2026-07-17T16:59:47.287484",
    "expiresAt": null
  },
  "timestamp": "2026-07-17 16:59:47"
}
```

DB 확인:

| 테이블 | 확인 내용 |
|---|---|
| document_permissions | id=2, user_id=3, document_id=1, can_read=true |
| user_document_access_cache | id=2, user_id=3, document_id=1, can_read=true, invalidated_at=NULL |

B가 `/me`를 호출한다.

```http
GET /permissions/documents/1/me
Authorization: Bearer {B token}
```

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "sources": ["USER_CACHE"]
  },
  "timestamp": "2026-07-17 17:02:56"
}
```

서버 로그:
```
[PERM] checkDoc doc=1 user=3 canRead=true canWrite=false canAdmin=false sources=[USER_CACHE] elapsed=36.749667ms
```

---

### 4.4 컬렉션 중복 문서 추가 방어

문서1은 이미 컬렉션1에 들어있는 상태에서 다시 추가를 시도한다.

```http
POST /collections/1/documents
Authorization: Bearer {A token}
```

```json
{ "documentId": 1 }
```

```json
{
  "status": 409,
  "code": "COLLECTION-002",
  "message": "이미 컬렉션에 추가된 문서입니다.",
  "method": "POST",
  "path": "/collections/1/documents",
  "success": false,
  "timestamp": "2026-07-17 17:09:56"
}
```

중복 추가 시 409를 반환한다.

---

### 4.5 컬렉션 ROLE 권한 부여 → USER_CACHE + ROLE 동시 확인

A가 컬렉션1에 role_id=4 READ 권한을 부여한다.

```http
POST /permissions/collections/1
Authorization: Bearer {A token}
```

```json
{
  "targetType": "ROLE",
  "userId": null,
  "roleId": 4,
  "departmentId": null,
  "permissionType": "READ",
  "expiresAt": null
}
```

```json
{
  "success": true,
  "status": 201,
  "data": {
    "permissionId": 1,
    "collectionId": 1,
    "targetType": "ROLE",
    "userId": null,
    "roleId": 4,
    "departmentId": null,
    "permissionType": "READ",
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "grantedBy": 2,
    "grantedAt": "2026-07-17T17:13:47.662175",
    "expiresAt": null
  },
  "timestamp": "2026-07-17 17:13:47"
}
```

B가 `/me`를 호출한다.

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "sources": ["USER_CACHE", "ROLE"]
  },
  "timestamp": "2026-07-17 17:14:26"
}
```

서버 로그:
```
[PERM] checkDoc doc=1 user=3 canRead=true canWrite=false canAdmin=false sources=[USER_CACHE, ROLE] elapsed=26.229709ms
```

두 경로가 동시에 sources에 수집된다.

---

### 4.6 USER 직접 권한 회수 → ROLE 경로 유지 확인

A가 B의 USER 직접 권한(permissionId=2)을 회수한다.

```http
DELETE /permissions/documents/1/2
Authorization: Bearer {A token}
```

응답: `204 No Content`

B가 `/me`를 호출한다.

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": true,
    "canWrite": false,
    "canAdmin": false,
    "sources": ["ROLE"]
  },
  "timestamp": "2026-07-17 17:18:16"
}
```

서버 로그:
```
[PERM] checkDoc doc=1 user=3 canRead=true canWrite=false canAdmin=false sources=[ROLE] elapsed=33.014958ms
```

USER 직접 권한이 없어도 ROLE 경로로 접근이 유지된다. user_document_access_cache에서 해당 행의 invalidated_at에 시각이 찍혀 무효화됐음을 DB에서 확인했다.

---

### 4.7 컬렉션에서 문서 제거 → 접근 차단 확인

A가 컬렉션1에서 문서1을 제거한다.

```http
DELETE /collections/1/documents/1
Authorization: Bearer {A token}
```

응답: `204 No Content`

B가 `/me`를 호출한다.

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": false,
    "canWrite": false,
    "canAdmin": false,
    "sources": []
  },
  "timestamp": "2026-07-17 17:22:20"
}
```

서버 로그:
```
[PERM] checkDoc doc=1 user=3 canRead=false canWrite=false canAdmin=false sources=[] elapsed=35.6455ms
```

컬렉션과 문서의 연결이 끊기면 ROLE 경로도 차단된다. 문서 자체는 삭제되지 않고 collection_documents 행만 삭제됐음을 DB에서 확인했다.

---

### 4.8 컬렉션 삭제 (soft delete) 확인

문서1을 컬렉션1에 다시 추가한 뒤 컬렉션을 삭제한다.

```http
POST /collections/1/documents
Authorization: Bearer {A token}
```

```json
{
  "success": true,
  "status": 201,
  "data": {
    "collectionId": 1,
    "documentId": 1,
    "addedBy": 2,
    "addedAt": "2026-07-17T17:24:25.353756"
  },
  "timestamp": "2026-07-17 17:24:25"
}
```

```http
DELETE /collections/1
Authorization: Bearer {A token}
```

응답: `204 No Content`

DB 확인:

```
collections: id=1, status=DELETED, deleted_at=2026-07-17 17:24:59.612
```

컬렉션 레코드는 삭제되지 않고 status와 deleted_at만 변경됐다.

컬렉션 삭제 후 B가 `/me`를 호출한다.

```json
{
  "success": true,
  "status": 200,
  "data": {
    "documentId": 1,
    "canRead": false,
    "canWrite": false,
    "canAdmin": false,
    "sources": []
  },
  "timestamp": "2026-07-17 18:34:10"
}
```

collection_permissions 행이 삭제돼 ROLE live 조회가 차단되고 접근이 완전히 막혔다.

---

### 4.9 컬렉션 목록에서 DELETED 필터링 확인

```http
GET /collections
Authorization: Bearer {A token}
```

```json
[]
```

DELETED 상태인 컬렉션1은 목록에 포함되지 않는다.

---

## 5. 자동 테스트 결과

별도 테스트 이슈로 분리 예정이다.

---

## 6. 최종 결론

| 완료 기준 | 결과 |
|---|---|
| OWNER → sources: ["OWNER"], 모두 true | ✅ |
| PUBLIC → canRead만 true, sources: ["PUBLIC"] | ✅ |
| USER 권한 부여 → user_document_access_cache 즉시 생성 | ✅ |
| USER 권한 부여 → sources: ["USER_CACHE"] | ✅ |
| ROLE + USER 동시 → sources: ["USER_CACHE", "ROLE"] | ✅ |
| USER 회수 후 ROLE 경로 유지 → sources: ["ROLE"] | ✅ |
| 컬렉션에서 문서 제거 → sources: [] | ✅ |
| 컬렉션 삭제 → soft delete (status=DELETED) | ✅ |
| DELETED 컬렉션 목록 제외 | ✅ |
| 중복 문서 추가 → 409 | ✅ |
