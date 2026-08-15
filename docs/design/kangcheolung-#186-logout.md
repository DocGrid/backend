# #186 로그아웃 API 구현 + 프론트 연동

closes #186

---

## 배경

백엔드에 로그아웃 API가 없었다. JWT는 완전 stateless(`JwtProvider`)라 만료(기본 1시간) 전까지는 어떤 토큰도 무효화할 방법이 없었고, 프론트(`AuthProvider.tsx`)의 `logout()`도 `sessionStorage`에서 토큰을 지우는 클라이언트 전용 정리에 불과했다. 사용자가 로그아웃해도 탈취된 토큰이 있다면 만료 전까지 계속 유효하다는 문제가 있었다.

## 설계 결정

- **무효화 저장소: Redis.** DB 블랙리스트 테이블 대안도 검토했으나(기존 `mcp_access_tokens` 발급/폐기 패턴 재사용 가능), Redis의 TTL 자동 만료가 로그아웃처럼 "곧 사라질 데이터"에 더 정확히 맞고, `JwtAuthenticationFilter`가 매 요청마다 조회하는 경로라 인메모리 조회 속도가 유리하다. 이 프로젝트에 Redis 의존성이 아직 없었지만 docker-compose에 서비스 하나 추가하는 비용은 크지 않아 채택했다.
- **무효화 방식**: `JwtProvider.generateToken`에 `jti`(UUID) claim을 추가하고, 로그아웃 시 그 `jti`를 Redis에 `TTL = 토큰 잔여 만료시간`으로 저장한다. `JwtAuthenticationFilter`는 매 요청마다 `jti`가 블랙리스트에 있는지 확인해 인증 여부를 결정한다.
- **Redis 장애 정책: fail-open.** 블랙리스트 조회가 실패하면(Redis 다운 등) `log.error` 남기고 정상 인증 흐름을 계속 진행한다. Redis를 인증 전체의 단일 장애점으로 만들지 않기 위함이며, 어차피 토큰은 1시간 내 자연 만료된다.
- **Redis 설정**: Spring Boot가 `spring-boot-starter-data-redis` + `spring.data.redis.host/port` 설정만으로 `StringRedisTemplate` 빈을 자동 구성해준다. MinIO처럼 별도 `RedisConfig`가 필요 없다 (MinIO는 Boot starter가 없어서 수동 설정한 것).
- **원본 토큰 접근**: 기존 `JwtAuthenticationFilter`는 인증된 `userId`만 `Authentication.details`에 저장하고 원본 토큰 문자열은 어디에도 남기지 않았다. 로그아웃 컨트롤러에서 토큰이 다시 필요해, filter의 private `resolveToken()` 로직을 `JwtProvider.resolveToken(HttpServletRequest)` 정적 메서드로 옮겨 filter/controller 양쪽에서 재사용한다.
- **에러 코드**: 신규 `ErrorCode` 없음. `/auth/logout`은 `SecurityConfig`의 `anyRequest().authenticated()`에 걸려 무효/만료 토큰은 필터 단계에서 이미 401로 막힌다.
- **프론트**: 기존 `logout()`(client-only 정리)은 `refresh()` 실패·`AUTH_EXPIRED_EVENT` 경로처럼 토큰이 이미 무효인 자동 정리 상황에 그대로 쓴다. 사용자가 명시적으로 로그아웃 버튼을 누르는 경로에만 백엔드 API를 먼저 호출하는 `signOut()`을 새로 추가했다. 자동 정리 경로에서까지 백엔드를 호출하면 이미 무효화된 토큰으로 불필요한 401만 유발한다.

## API 명세

```http
POST /auth/logout
Authorization: Bearer {token}
```

현재 사용 중인 액세스 토큰을 무효화한다. 이후 같은 토큰으로 요청하면 만료 전이라도 인증되지 않는다.

**응답**: `204 No Content` (본문 없음)

**에러 케이스**

| 상황 | 응답 |
| --- | --- |
| `Authorization` 헤더 없음 / 토큰이 유효하지 않음(서명 위조·만료) | `401 UNAUTHORIZED` — `JwtAuthenticationFilter` 단계에서 인증되지 않아 `SecurityConfig`가 거부 |
| 이미 로그아웃(블랙리스트 등록)된 토큰으로 재요청 | `401 UNAUTHORIZED` — 위와 동일하게 필터에서 걸러짐 |
| Redis 장애 | 로그아웃 요청 자체는 예외 없이 처리되지 않고 실패할 수 있으나(블랙리스트 등록 실패), 인증 필터의 블랙리스트 *조회*는 fail-open이라 다른 모든 API의 인증에는 영향 없음 |

## 변경 파일

- `backend/build.gradle` — `spring-boot-starter-data-redis` 추가
- `docker-compose.yml`, `.env.example` — `redis` 서비스/환경변수 추가
- `backend/src/main/resources/application.yml` — `spring.data.redis.host/port/password`
- `JwtProvider.java` — `jti` claim 추가, `resolveToken(HttpServletRequest)` 정적 메서드 추가
- 신규 `TokenBlacklistService.java` (`domain/auth/jwt`) — Redis 기반 블랙리스트 등록/조회
- `JwtAuthenticationFilter.java` — 블랙리스트 검증(fail-open) 추가
- `SecurityConfig.java` — `TokenBlacklistService` 주입
- `AuthCommandService.java` — `logout(String token)` 추가
- `AuthController.java` — `POST /auth/logout` 추가
- `frontend/app/components/AuthProvider.tsx` — `signOut()` 추가
- `frontend/app/features/AccountPage.tsx` — 로그아웃 버튼을 `signOut()`에 연결

## 테스트

- `AuthCommandServiceTest` — 로그아웃 정상/무효 토큰 케이스
- 신규 `TokenBlacklistServiceTest` — Redis 등록/조회 단위 테스트
- 신규 `JwtAuthenticationFilterTest` — 정상 인증, 블랙리스트 토큰 거부, Redis 장애 시 fail-open 3케이스
- 신규 `AuthLogoutIntegrationTest`(`@Tag("integration")`) — 실제 Postgres+Redis로 로그인→로그아웃→블랙리스트 등록 확인
- `./backend/gradlew -p backend test` 전체 통과 확인
