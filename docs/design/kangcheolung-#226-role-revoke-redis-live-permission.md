# #226 역할 회수 API + Redis 캐시 기반 권한 즉시 반영

closes #226

---

## 배경

QA 시나리오 2(관리자 사용자 관리) 진행 중 두 가지를 발견했다.

**1) 역할을 되돌릴 방법이 없었다.** `UserRoleCommandService`엔 `assignRole()`만 있고 `revoke`가 없어서, 관리자가 역할을 잘못 부여해도 되돌릴 API가 어디에도 없었다.

**2) 역할을 부여/회수해도 로그아웃 전까지 실제 권한이 안 바뀌었다.** `JwtAuthenticationFilter`/`StompAuthChannelInterceptor`가 `hasRole(...)` 인가를 로그인 시점 JWT에 박제된 `roles` claim(`JwtProvider.generateToken()`)만으로 판단했기 때문이다. `GET /auth/me`는 매 요청 DB를 새로 읽어 화면엔 바로 반영되는 것처럼 보이지만, 실제 `/admin/**` 등 Spring Security의 `hasRole` 판정은 토큰 발급 시점 값 그대로였다 — 실측으로 재현: 관리자가 이미 로그인해있던 사용자를 ADMIN으로 승격시키고 새로고침해도, 그 사용자는 `/admin/users` 같은 관리자 API를 호출하면 여전히 403이 났다.

두 번째 문제가 **회수 쪽에서 더 심각하다** — 관리자가 잘못 준 ADMIN 권한을 회수해도, 대상자가 로그아웃하지 않는 한 계속 관리자 기능을 쓸 수 있는 보안 문제가 된다. 그래서 이 이슈는 역할 회수 API와 "재로그인 없이 즉시 반영" 인프라를 함께 다룬다.

```text
관리자 → DELETE /admin/users/{userId}/roles/{roleCode} (JWT, ADMIN role)
       → AdminUserController → UserRoleCommandService.revokeRole()
       → DB에서 UserRole 삭제 + RoleAuthorityService 캐시 무효화
       → 대상 사용자는 로그아웃 없이 다음 요청부터 바로 권한 변경 반영
```

---

## 설계 방향 — JWT는 신원만, role은 Redis+DB로 매 요청 조회

핵심 아이디어는 **JWT에서 인가(role) 판단 근거를 완전히 분리**하는 것이다.

```text
지금까지: JwtAuthenticationFilter가 claims.get("roles")로 인가 판단
          → 로그인 시점에 박제된 값, DB가 바뀌어도 재로그인 전까진 그대로

바꾼 뒤:  JwtAuthenticationFilter가 RoleAuthorityService.getRoles(userId) 호출
          → Redis 캐시(30초 TTL) 있으면 반환, 없으면 DB 조회 후 캐싱
          → assignRole()/revokeRole()이 DB 저장 직후 해당 유저 캐시를 즉시 delete
          → 다음 요청부터 무조건 DB에서 재조회되어 반영됨
```

JWT는 이제 `userId`/`email`(신원 확인)만 담당하고, `roles` claim은 완전히 제거했다. 인가 판단은 매 요청 `RoleAuthorityService`를 거친다.

### 왜 Redis인가 — 새 인프라 없이 기존 것 재사용

이 프로젝트는 이미 로그아웃 시 JWT를 블랙리스트에 넣는 용도로 Redis(`StringRedisTemplate`, `TokenBlacklistService`)를 쓰고 있다. `RoleAuthorityService`도 같은 `StringRedisTemplate` 빈을 그대로 재사용해서, 신규 인프라 도입 없이 캐싱 레이어를 추가했다.

### 캐시 설계 — 짧은 TTL + 능동 무효화의 조합

```java
private static final String KEY_PREFIX = "auth:roles:";
private static final Duration TTL = Duration.ofSeconds(30);

public List<String> getRoles(Long userId) {
    String cached = readCache(userId);
    if (cached != null) {
        return cached.isBlank() ? List.of() : Arrays.asList(cached.split(","));
    }
    List<String> roles = userRoleRepository.findRoleCodesByUserId(userId);
    writeCache(userId, roles);
    return roles;
}

public void invalidate(Long userId) {
    try {
        redisTemplate.delete(KEY_PREFIX + userId);
    } catch (Exception e) {
        log.error("Redis role 캐시 무효화 실패, userId={}: {}", userId, e.getMessage());
    }
}
```

- **TTL 30초**는 안전망일 뿐, 실제 즉시 반영은 **능동 무효화**(`assignRole()`/`revokeRole()`이 DB 저장 직후 `invalidate()` 호출)로 만든다. TTL이 만료되기를 기다릴 필요 없이, role이 바뀐 바로 다음 요청부터 캐시 미스가 나서 DB를 재조회한다.
- 매 인증 요청마다 DB를 직접 때리지 않고 Redis를 우선 조회하므로, role 조회로 인한 DB 부하 증가를 최소화했다.
- 빈 role 목록(`""`)과 "캐시 없음"(`null`)을 구분해서, role이 0개인 사용자도 매번 DB를 다시 조회하지 않고 캐시로 처리한다.

### Redis 장애에도 인증이 끊기면 안 된다 — TokenBlacklistService와 동일한 방어 패턴

`RoleAuthorityService`는 이제 **모든 인증된 요청의 critical path**에 있다. 기존 `TokenBlacklistService.isBlacklisted()`가 Redis 장애 시 예외를 던지지 않고 "블랙리스트 아님"으로 안전하게 폴백하는 것과 동일하게, `RoleAuthorityService`도 Redis 읽기/쓰기/삭제 전부를 `try/catch`로 감싸서 장애 시 DB 조회로 폴백하도록 했다. 이 방어 로직이 없었다면 Redis가 죽는 순간 전체 API가 500으로 막혔을 것이다 — 구현 중간에 발견해서 추가했다.

```java
private String readCache(Long userId) {
    try {
        return redisTemplate.opsForValue().get(KEY_PREFIX + userId);
    } catch (Exception e) {
        log.error("Redis role 캐시 조회 실패, DB로 폴백합니다. userId={}: {}", userId, e.getMessage());
        return null;
    }
}
```

---

## 신규/변경 파일

### 1. 신규 — `domain/auth/jwt/RoleAuthorityService.java`

`TokenBlacklistService`와 같은 패키지·스타일. `getRoles(Long userId)` / `invalidate(Long userId)` 두 개의 공개 메서드만 가진다.

### 2. `JwtProvider.java` — `roles`를 더 이상 토큰에 담지 않음

```java
// Before
public String generateToken(Long userId, String email, List<String> roles) {
    ...
    .claim("roles", roles)
    ...
}

// After
public String generateToken(Long userId, String email) {
    ...
    // roles claim 제거
}
```

### 3. `JwtAuthenticationFilter.java` / `StompAuthChannelInterceptor.java` — 인가 소스 교체

```java
// Before
List<String> roles = (List<String>) claims.get("roles");

// After
List<String> roles = roleAuthorityService.getRoles(userId);
```

`JwtAuthenticationFilter`는 `SecurityConfig`에서 `new`로 직접 생성되는 필터라 생성자에 `RoleAuthorityService`를 3번째 인자로 추가했고, `StompAuthChannelInterceptor`는 스프링 빈이라 필드 추가만으로 자동 주입됐다.

**주의(이슈 To-do엔 없었지만 필수 종속 변경)**: JWT에서 `roles` claim을 제거하면 `StompAuthChannelInterceptor`의 `claims.get("roles")`가 `null`을 반환해 `.stream()` 호출 시 NPE가 난다. WebSocket 인증 경로도 같이 고쳐야만 컴파일·런타임 모두 안전했다.

### 4. `SecurityConfig.java`

`RoleAuthorityService`를 주입받아 `new JwtAuthenticationFilter(jwtProvider, tokenBlacklistService, roleAuthorityService)`로 변경.

### 5. `AuthCommandService.login()`

`jwtProvider.generateToken(user.getId(), user.getEmail())` (roles 인자 제거). `LoginResponse`에 담기는 `roles`는 원래부터 `userRoleRepository.findRoleCodesByUserId()`로 별도 조회하던 값이라 변경 없음 — JWT 토큰 내부와 로그인 응답 바디는 애초에 다른 값이었다.

### 6. 역할 회수 API

- `ErrorCode`에 `ROLE_NOT_ASSIGNED(HttpStatus.NOT_FOUND, "ROLE-004", "부여되지 않은 역할입니다.")` 추가
- `UserRoleRepository`에 `Optional<UserRole> findByUserIdAndRoleCode(Long userId, String roleCode)` 추가
- `UserRoleCommandService.revokeRole(Long targetUserId, String roleCode)`:
  1. 대상 사용자 조회 → 없으면 `USER_NOT_FOUND`
  2. `findByUserIdAndRoleCode`로 부여 기록 조회 → 없으면 `ROLE_NOT_ASSIGNED`
  3. `userRoleRepository.delete(userRole)`
  4. `roleAuthorityService.invalidate(targetUserId)` — 캐시 무효화, 다음 요청부터 즉시 반영
  5. 남은 역할을 재조회해 `UserRoleResponse`로 반환 (`assignRole()`과 대칭 구조)
- `assignRole()` 끝에도 동일하게 `roleAuthorityService.invalidate(targetUserId)`를 추가했다 (이전엔 캐시 개념 자체가 없어서 무효화 호출이 없었다).
- `AdminUserController`에 `DELETE /admin/users/{userId}/roles/{roleCode}` 추가. 기존 `/admin/**` → `hasRole("ADMIN")` 규칙이 그대로 적용된다.

---

## API 명세

### DELETE /admin/users/{userId}/roles/{roleCode} — 역할 회수

```text
Authorization: Bearer {JWT, ADMIN role}
```

```json
// 200 OK
{
  "success": true,
  "status": 200,
  "data": {
    "userId": 10,
    "email": "hong@example.com",
    "name": "홍길동",
    "roles": ["USER"]
  }
}
```

### 에러 케이스

| 상황 | 코드 | HTTP |
| --- | --- | --- |
| ADMIN 권한 없이 호출 | 없음 (Spring Security 필터에서 차단) | 403 |
| 존재하지 않는 `userId` | `USER-001` (`USER_NOT_FOUND`) | 404 |
| 부여되지 않은 역할 회수 시도 | `ROLE-004` (`ROLE_NOT_ASSIGNED`) | 404 |

---

## 프론트엔드

`/admin/users` 페이지의 사용자 목록에서 각 역할 칩(pill) 옆에 회수(×) 버튼을 추가했다(`AdminPages.tsx`). 클릭 시 `window.confirm`으로 확인 후 `DELETE /admin/users/{userId}/roles/{roleCode}` 호출 → 성공하면 토스트 알림 + 목록 새로고침. 기존 문서/컬렉션 삭제 액션(`CollectionsPage.tsx`, `DocumentsPage.tsx`)과 동일하게 `window.confirm` 패턴을 재사용했다. 회수 중인 칩만 개별적으로 비활성화되도록 `revokingKey` state로 `"{userId}:{roleCode}"` 단위 잠금 처리를 했다.

---

## 검증

- `./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.auth.*" --tests "com.opensource.docgrid.domain.user.*" --tests "...IndexingJobAdminControllerTest" --tests "...IndexingJobAdminQueryControllerTest" --tests "...SyncAdminControllerTest" --tests "...WorkerAdminControllerTest"`: 전부 통과
  - 신규: `RoleAuthorityServiceTest`(캐시 히트/미스/무효화/Redis 장애 폴백 4건), `UserRoleCommandServiceTest`(부여/회수 성공·실패 5건), `AdminUserControllerTest` DELETE 케이스 3건
  - 기존 테스트 중 JWT 시그니처 변경으로 깨졌던 것 전부 수정: `JwtAuthenticationFilterTest`, `StompAuthChannelInterceptorTest`, `AuthCommandServiceTest`
  - `@Import(SecurityConfig.class)`를 쓰는 `@WebMvcTest` 5곳(`AdminUserControllerTest`, `IndexingJobAdminControllerTest`, `IndexingJobAdminQueryControllerTest`, `SyncAdminControllerTest`, `WorkerAdminControllerTest`)에 `RoleAuthorityService` 신규 의존성이 추가되면서 컨텍스트 로딩이 깨져(`NoSuchBeanDefinitionException`) 전부 `@MockitoBean` 추가로 해결 — `#96`(MCP 인증 인프라) 이슈에서 `McpAccessTokenCommandService` 추가 때 겪은 것과 동일한 패턴.
- `./backend/gradlew -p backend test`(필터 없는 전체 983개) 1회 실행: 이번 변경과 무관한 기존 결함 2건만 실패(`UserRepositoryTest`, `RagJobWorkerConcurrentQueueIntegrationTest`) — 둘 다 근본 원인까지 확인 완료(아래 "부수적으로 발견한 것" 참고), 이번 PR 범위 밖이라 손대지 않음
- 프론트: `npx tsc --noEmit` — `AdminPages.tsx`/`globals.css` 관련 타입 에러 없음
- 실제 시나리오 재현 확인(로컬): 관리자가 이미 로그인해있던 사용자를 ADMIN으로 승격 → 재로그인 없이 새 요청부터 `/admin/**` 정상 접근 확인 → 회수 → 재로그인 없이 즉시 403으로 돌아가는지 확인 (QA에서 처음 겪었던 그 시나리오가 해결됨을 실제로 검증)

### 부수적으로 발견한 것 (이번 PR 범위 밖, 기록만)

- **`build.gradle`의 `excludeTags`에 `integration`이 빠져 있다.** `@Tag("integration")`인 테스트 33개가 `testing_guide.md`가 문서화한 것과 달리 기본 `./gradlew test`에 항상 섞여 돈다. `-Dgroups=integration`으로 분리 실행한다는 문서 내용도 실제로는 `build.gradle`에 그 시스템 프로퍼티를 읽는 태스크가 없어 작동하지 않는 상태였다. `localE2eTest`/`minioIntegrationTest` 같은 기존 `includeTags` 전용 태스크 패턴을 그대로 따라 `integrationTest` 태스크를 신설하고 기본 `test`의 `excludeTags`에 `integration`을 추가하면 해결되지만, 33개 클래스의 기본 실행 여부를 바꾸는 저장소 전체 정책 변경이라 이 PR에 묶지 않았다.
- **`UserRepositoryTest`가 스위트 전체를 돌릴 때 간헐적으로 실패하는 근본 원인을 확인했다.** `docgrid_test` 스키마에 과거 integration 테스트들이 커밋하고 정리하지 않은 row가 42개 쌓여 있고, 정작 이 테스트의 쿼리 호출(`PageRequest.of(0, 20)`)엔 정렬(`Sort`) 지정이 없다. 그래서 "1페이지 20개"가 어떤 20개인지 PostgreSQL이 보장해주지 않고, 방금 만든 테스트 row가 그 안에 든다는 보장이 없다. 코드 결함이 아니라 테스트 설계 결함(정렬 없는 페이지 조회 + 스키마 미정리) 두 가지가 겹친 것으로, 이 PR과 무관해 손대지 않았다.
- `/admin/**` 미인증(403) 응답이 Spring Security 기본 포맷이라 프론트가 에러 메시지를 못 뽑아내 "요청을 처리하지 못했습니다"로만 뭉뚱그려 보이는 문제 — 필요시 별도 이슈.

---

## 남은 이슈 / TODO

- 부서(department)도 사람이 승격/강등되듯 바뀌는 값인데, 이번 이슈에서 손댄 role 캐싱과 달리 department 기반 권한(`DocumentPermission`/`CollectionPermission`, `targetType=DEPARTMENT`)은 원래부터 매 요청 live JOIN으로 판단해 캐시가 없다 — 이번 즉시반영 작업과 정합성 문제 없음, 별도 조치 불필요함을 확인.
- 위 "부수적으로 발견한 것" 3건은 각각 독립적인 후속 이슈 후보.

### 알려진 한계 (CodeRabbit 리뷰에서 지적, 의도적으로 이번 PR에서 해결하지 않음)

캐시 무효화를 트랜잭션 커밋 이후로 미루는 수정(`invalidateAfterCommit`)으로 가장 흔한 시나리오는 해결했지만, 아래 세 가지는 여전히 남아있는 엣지 케이스다. 셋 다 "정합성을 100% 보장하려면 상당한 설계 변경이 필요한데, 실제 발생 확률과 영향 범위에 비해 비용이 큰" 케이스라 의도적으로 남겨뒀다.

1. **캐시 미스 재경쟁**: 요청 A가 캐시 미스로 DB에서 role을 읽는 도중(요청이 오래 걸리는 경우), 그 사이 요청 B가 회수+커밋+무효화를 전부 끝내고, 이후 A가 자신이 읽은 옛날 값을 캐시에 쓰면 그 값이 다시 TTL(30초)만큼 살아난다. `invalidateAfterCommit`이 가장 흔한 형태(무효화가 커밋보다 먼저 나가는 것)는 막았지만, 이 순서 자체의 재경쟁까지 막으려면 캐시 값에 사용자별 버전/세대 번호를 같이 저장하고 쓰기 전에 검증하는 구조가 필요하다 — 이번 PR 범위를 넘어서는 설계 변경이라 남겨둔다.
2. **Redis 삭제 자체가 실패하는 경우**: `invalidate()`는 Redis 장애 시 로그만 남기고 넘어가도록 의도적으로 설계했다(가용성 우선, `RoleAuthorityService`의 "Redis 장애에도 인증이 끊기면 안 된다" 참고). 그 순간 무효화가 실제로 안 먹히면, Redis가 복구돼도 TTL이 끝날 때까지 옛날 role이 남을 수 있다. 정합성을 완전히 지키려면 Redis 장애 시 "이 사용자는 캐시 신뢰 불가" 마커를 다른 저장소(예: DB)에 남기는 식의 이중화가 필요한데, 이 역시 이번 PR 범위 밖이다.
3. **이미 연결된 WebSocket(대시보드) 세션**: `StompAuthChannelInterceptor`는 STOMP CONNECT 시점에만 권한을 확인해서 세션에 붙여두고, 이후 프레임에서는 재검증하지 않는다. 따라서 역할을 회수해도 이미 연결돼 있던 대시보드 WebSocket 세션은 재연결 전까지 예전 권한으로 계속 동작한다. 완전히 고치려면 역할 변경 시 대상자의 기존 세션을 강제 종료하거나 프레임마다 재검증하는 구조가 필요하며, 이번 이슈의 핵심 범위(HTTP `/admin/**`)를 넘어서는 별도 작업이다.

세 가지 다 별도 이슈로 분리할 수 있는 후보다.
