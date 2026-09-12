# #324 컬렉션/문서 권한 부여 시 중복 부여 방지

closes #324

---

## 배경

#322 작업 중 권한 도메인을 다시 훑어보다가, `CollectionPermissionCommandService`/
`DocumentPermissionCommandService`의 `grantPermission`이 저장 전에 "이 대상에게
이미 유효한 권한이 있는지" 확인하는 로직이 전혀 없다는 걸 발견했다. DB에도
`(collection_id/document_id, target_type, user_id/role_id/department_id)` 조합에
대한 유니크 제약이 없어서, 같은 대상에게 권한 부여를 여러 번 요청하면(더블클릭,
요청 재시도 등) 권한 행이 그대로 중복 생성된다.

---

## 문제상황

단순 데이터 중복이 아니라 회수(revoke)가 실질적으로 무력화될 수 있는 문제였다.
`PermissionType`(READ/WRITE/ADMIN)은 계층적이다:

```java
// CollectionPermissionCommandService.resolvePermissions / DocumentPermissionCommandService.resolvePermissions
private boolean[] resolvePermissions(PermissionType type) {
    return switch (type) {
        case READ  -> new boolean[]{true, false, false};
        case WRITE -> new boolean[]{true, true, false};   // WRITE는 READ도 포함
        case ADMIN -> new boolean[]{true, true, true};    // ADMIN은 전부 포함
    };
}
```

같은 대상이 서로 다른 레벨의 권한 행을 동시에 가질 논리적 이유가 없는데, 이 상태에서
권한 회수는 `permissionId` 단위로 동작한다(`revokePermission(collectionId, permissionId,
revokerId)`). 예를 들어 어떤 유저에게 READ 권한이 걸려있는 상태에서 실수로 WRITE 권한을
또 grant하면 그 유저는 READ 행 1개 + WRITE 행 1개를 갖게 되고, 이후 관리자가
"WRITE 권한 회수"만 누르면 WRITE 행만 삭제되고 READ 행은 그대로 남아 **회수했는데도
여전히 읽기 접근이 가능한** 상황이 생길 수 있다. 프론트(`PermissionsPage.tsx`)도
grant 폼 + 개별 회수 버튼만 있고 "권한 레벨 수정" 플로우가 없어서, 레벨을 바꾸려면
grant를 다시 누르는 방식으로 쓰일 여지가 있었다.

---

## 설계

"같은 대상(targetType + user_id/role_id/department_id)에게 만료되지 않은 권한이
이미 있으면, permissionType과 무관하게 새 grant 요청을 409로 거부"하는 규칙을 택했다.

- **permissionType은 보지 않는다**: 계층적 구조상 레벨이 달라도 동시에 존재할 이유가
  없다는 게 근거다. 정확히 같은 레벨일 때만 막으면(예: READ 있는데 WRITE는 허용) 위에서
  설명한 회수 무력화 문제가 그대로 남는다.
- **만료된 권한은 중복으로 치지 않는다**: `(expiresAt IS NULL OR expiresAt > CURRENT_TIMESTAMP)`
  조건으로 걸러서, 기존 권한이 만료된 뒤에는 재부여가 막히지 않게 했다 — 기존
  `existsUserReadPermission` 등 다른 권한 판정 쿼리들과 동일한 만료 처리 패턴이다.
- **레벨을 바꾸고 싶으면 기존 권한을 먼저 회수하고 새로 grant해야 한다.** "권한 레벨
  수정" API를 새로 만드는 건 이번 스코프가 아니다.
- `addDocument`의 `COLLECTION_DOCUMENT_ALREADY_EXISTS`(409)와 동일한 패턴을 그대로
  따랐다 — 이 도메인에 이미 있는 "이미 존재하는 관계에 대한 중복 요청" 처리 컨벤션.

---

## 해결 (구현)

### 1. `ErrorCode` — 409 에러코드 2개 추가

```java
COLLECTION_PERMISSION_ALREADY_GRANTED(HttpStatus.CONFLICT, "PERMISSION-005", "이미 이 대상에게 부여된 컬렉션 권한이 있습니다."),
DOCUMENT_PERMISSION_ALREADY_GRANTED(HttpStatus.CONFLICT, "PERMISSION-006", "이미 이 대상에게 부여된 문서 권한이 있습니다."),
```

### 2. `CollectionPermissionRepository`/`DocumentPermissionRepository` — 대상별 확인 쿼리 3개씩 추가

```java
@Query("""
        SELECT COUNT(cp) > 0 FROM CollectionPermission cp
        WHERE cp.collection.id = :collectionId
          AND cp.targetType = com.opensource.docgrid.domain.permission.enums.PermissionTargetType.USER
          AND cp.user.id = :userId
          AND (cp.expiresAt IS NULL OR cp.expiresAt > CURRENT_TIMESTAMP)
        """)
boolean existsActiveUserGrant(@Param("collectionId") Long collectionId, @Param("userId") Long userId);
```

ROLE/DEPARTMENT 버전도 동일한 형태로 추가(`existsActiveRoleGrant`, `existsActiveDeptGrant`).
`DocumentPermissionRepository`에는 기존에 USER 대상 확인 쿼리가 아예 없었다(문서 캐시로만
판단했기 때문 — 주석 "USER 대상은 없음(캐시로 판단)" 참고) — 이번에 중복 확인 용도로
`existsActiveUserGrant`를 처음 추가했다.

### 3. `CollectionPermissionCommandService`/`DocumentPermissionCommandService` — grant 전 중복 체크

```java
if (hasActiveGrant(collectionId, request.targetType(), targetUser, targetRole, targetDepartment)) {
    throw new DocGridException(ErrorCode.COLLECTION_PERMISSION_ALREADY_GRANTED);
}

private boolean hasActiveGrant(Long collectionId, PermissionTargetType targetType,
                                User targetUser, Role targetRole, Department targetDepartment) {
    return switch (targetType) {
        case USER -> collectionPermissionRepository.existsActiveUserGrant(collectionId, targetUser.getId());
        case ROLE -> collectionPermissionRepository.existsActiveRoleGrant(collectionId, targetRole.getId());
        case DEPARTMENT -> collectionPermissionRepository.existsActiveDeptGrant(collectionId, targetDepartment.getId());
    };
}
```

대상 엔티티(targetUser/targetRole/targetDepartment)를 조회한 직후, `CollectionPermission`/
`DocumentPermission` 엔티티를 만들기 전에 체크한다 — `validateTargetType`(요청 형식 검증) →
대상 존재 확인 → **중복 확인**(신규) → 저장 순서.

---

## 로컬 검증

`./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.permission.*"` → `BUILD SUCCESSFUL`

- `CollectionPermissionCommandServiceTest`: `grantPermission_throws_when_activeUserGrantAlreadyExists`,
  `grantPermission_throws_when_activeRoleGrantAlreadyExists`(신규) — 이미 유효한 권한이 있으면
  409 예외를 던지고 `save`가 호출되지 않는지 검증. 기존 grant 테스트들(`existsActiveUserGrant`
  등을 스텁하지 않음 → Mockito 기본값 `false`)은 수정 없이 그대로 통과 — 중복이 없는 정상
  케이스를 이미 암묵적으로 커버하고 있었다.
- `DocumentPermissionCommandServiceTest`: 동일한 패턴으로 `grantPermission_throws_when_activeUserGrantAlreadyExists`,
  `grantPermission_throws_when_activeRoleGrantAlreadyExists`(신규) 추가.
- 신규 `existsActiveUserGrant`/`existsActiveRoleGrant`/`existsActiveDeptGrant` 쿼리 자체(만료
  제외 로직 포함)는 이 도메인의 다른 exists 계열 쿼리들과 마찬가지로 리포지토리 레벨 테스트
  없이 서비스 단위 테스트(mock)로만 커버한다 — 이 파일의 기존 컨벤션을 그대로 따름.

---

## 설계 결정 요약

- permissionType 무관하게 대상 단위로 중복을 막았다 — 계층적 권한 구조에서 "다른 레벨로
  중복 보유"가 항상 무의미하고, 회수 시 혼란(레벨 하나만 회수해도 다른 레벨이 남는 문제)의
  근본 원인이기 때문.
- 만료된 권한은 중복 판정에서 제외해 재부여를 막지 않는다.
- `addDocument`의 기존 409 컨벤션(`COLLECTION_DOCUMENT_ALREADY_EXISTS`)을 그대로 재사용했다 —
  새로운 에러 처리 패턴을 만들지 않았다.

## 남은 이슈 / TODO

- "권한 레벨 수정"(예: READ→WRITE로 바꾸기) 전용 API는 없다 — 지금은 회수 후 재부여로만
  가능하다. 필요해지면 별도 이슈로 다룬다.
- DB 레벨 유니크 제약(`(collection_id, target_type, user_id/role_id/department_id)` 부분
  유니크 인덱스 등)은 추가하지 않았다 — 애플리케이션 레벨 체크로 막았지만, 동시 요청
  레이스 컨디션(두 요청이 거의 동시에 들어와 둘 다 체크를 통과하는 경우)까지 막지는
  못한다. 실제로 문제가 되면 DB 제약을 추가하는 방향으로 보강할 것.

## 다음 단계

머지 후 `docs/test-results/`에 테스트 결과 문서 별도 작성(`docs-management.md` 컨벤션).
