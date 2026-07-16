# Issue #21 PermissionQueryService 설계

## 1. 목적

문서와 컬렉션에 대한 접근 권한을 통일된 판단 로직으로 처리한다. 모든 권한 판단은 이 서비스 하나를 통해 이루어진다.

```text
canReadDocument / canWriteDocument / canAdminDocument
canWriteCollection / canAdminCollection
```

```text
브랜치명: refactor/21
```

---

## 2. 설계 원칙

### 비대칭 캐싱

권한 판단 성능을 위해 USER 권한만 캐시(`user_document_access_cache`)에 저장한다.

```text
USER 권한  → 캐시 저장 (빠른 조회)
ROLE 권한  → 매 요청마다 live 조회
DEPT 권한  → 매 요청마다 live 조회
```

ROLE/DEPT는 구성원이 동적으로 바뀌기 때문에 캐시하지 않는다.

### 단락 평가 (Short-circuit)

단계별로 권한이 확인되면 즉시 `true`를 반환한다. 불필요한 DB 조회를 최소화한다.

```text
1단계에서 true → 2~5단계 쿼리 실행 안 함
```

---

## 3. 문서 권한 판단 단계

### canReadDocument (5단계)

```text
1단계: OWNER
  document.owner_user_id = 요청 userId → true

2단계: PUBLIC
  document.visibility = PUBLIC → true

3단계: USER 캐시
  user_document_access_cache에서 유효한 READ 캐시 존재 → true

4단계: ROLE live
  document_permissions에서 userId의 role READ 권한 존재
  OR collection_permissions에서 userId의 role READ 권한 존재 → true

5단계: DEPARTMENT live
  document_permissions에서 userId의 dept READ 권한 존재
  OR collection_permissions에서 userId의 dept READ 권한 존재 → true

모두 없으면 → false
```

### canWriteDocument (4단계)

WRITE는 PUBLIC 개념이 없으므로 4단계다.

```text
1단계: OWNER
2단계: USER 캐시 (canWrite=true)
3단계: ROLE live
4단계: DEPARTMENT live
```

### canAdminDocument (4단계)

```text
1단계: OWNER
2단계: USER 캐시 (canAdmin=true)
3단계: ROLE live
4단계: DEPARTMENT live
```

---

## 4. 컬렉션 권한 판단

컬렉션 판단에는 캐시가 없다. OWNER, USER 직접 권한, ROLE, DEPT를 순서대로 확인한다.

### canWriteCollection

```text
1. collection.owner_user_id = userId → true
2. collection_permissions에서 userId USER WRITE 권한 존재 → true
3. collection_permissions에서 userId의 ROLE WRITE 권한 존재 → true
4. collection_permissions에서 userId의 DEPT WRITE 권한 존재 → true
없으면 → false
```

### canAdminCollection

```text
1. collection.owner_user_id = userId → true
2. collection_permissions에서 userId USER ADMIN 권한 존재 → true
3. collection_permissions에서 userId의 ROLE ADMIN 권한 존재 → true
4. collection_permissions에서 userId의 DEPT ADMIN 권한 존재 → true
없으면 → false
```

---

## 5. 성능 측정 로그

각 단계 진입과 결과를 `System.nanoTime()`으로 측정해 ms 단위로 로그를 남긴다.

```text
[PERM] canRead owner=true doc=5 user=1 elapsed=0.12ms
[PERM] canRead cache=true doc=5 user=2 step2=0.05ms elapsed=0.18ms
[PERM] canRead denied doc=5 user=3 step5=1.20ms elapsed=2.45ms
```

각 단계별 소요 시간(`step1Ms`, `step2Ms`, ...)은 다음 단계 진입 전에 미리 캡처한다.

```java
long t2 = System.nanoTime();
double step1Ms = (t2 - t1) / 1_000_000.0; // 다음 단계 실행 전에 미리 계산
if (cacheRepository.existsValidReadCache(...)) { ... }
```

---

## 6. 구현 구조

```text
Service (Query)
- PermissionQueryService
  - canReadDocument(userId, documentId)
  - canWriteDocument(userId, documentId)
  - canAdminDocument(userId, documentId)
  - canWriteCollection(userId, collectionId)
  - canAdminCollection(userId, collectionId)
  - checkDocumentPermission(userId, documentId)  ← Issue #24에서 추가

Repository (읽기 전용 쿼리)
- UserDocumentAccessCacheRepository
  - existsValidReadCache / existsValidWriteCache / existsValidAdminCache
- DocumentPermissionRepository
  - existsRoleReadPermission / existsRoleWritePermission / existsRoleAdminPermission
  - existsDeptReadPermission / existsDeptWritePermission / existsDeptAdminPermission
- CollectionPermissionRepository
  - existsRoleReadPermissionForDocument / existsRoleWritePermissionForDocument / existsRoleAdminPermissionForDocument
  - existsDeptReadPermissionForDocument / existsDeptWritePermissionForDocument / existsDeptAdminPermissionForDocument
  - existsUserWritePermission / existsUserAdminPermission
  - existsRoleWritePermissionForCollection / existsRoleAdminPermissionForCollection
  - existsDeptWritePermissionForCollection / existsDeptAdminPermissionForCollection
```

---

## 7. 사용 위치

`PermissionQueryService`는 Command 계층에서 호출된다.

```text
CollectionCommandService.addDocument
  → canWriteCollection 확인

CollectionPermissionCommandService.grantPermission / revokePermission
  → canAdminCollection 확인

DocumentPermissionCommandService.grantPermission / revokePermission
  → canAdminDocument 확인
```

---

## 8. 캐시 유효 조건

```text
invalidated_at IS NULL
AND (expiresAt IS NULL OR expiresAt > CURRENT_TIMESTAMP)
```

두 조건을 모두 만족해야 유효한 캐시로 판단한다.

---

## 9. 완료 기준

- 문서 권한은 OWNER → PUBLIC → USER캐시 → ROLE → DEPT 순으로 판단한다.
- 앞 단계에서 권한이 확인되면 이후 단계 쿼리를 실행하지 않는다.
- 컬렉션 권한 판단에 ROLE/DEPT 경로가 포함된다.
- 각 단계 소요 시간이 ms 단위로 로그에 남는다.
- 성능 측정 타이밍은 다음 단계 실행 전에 캡처해 측정 오차가 없다.
