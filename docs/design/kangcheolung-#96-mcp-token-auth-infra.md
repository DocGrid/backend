# #96 MCP 인증 인프라 — API 키 발급/조회/폐기 + 인증 필터 (F-MCP-07, F-MCP-10)

closes #96

---

## 배경

MCP Server 블록의 두 번째 이슈. Issue 1(#93)에서 만든 도구 3개(`search_documents` 등)는 아직 "누가 호출했는지" 모르는 상태였다 — `/mcp` 엔드포인트를 임시로 `permitAll` 처리해뒀기 때문이다. 이 이슈는 그 자리를 실제 인증으로 채운다.

**두 갈래 인증이 필요한 이유**

DocGrid에는 이미 웹 로그인용 JWT가 있지만, JWT는 `expiration: 3600`(1시간)으로 짧게 설계돼 있어 Claude Desktop 설정 파일에 한 번 등록해두고 몇 달씩 재사용하는 시나리오엔 맞지 않는다. 그래서:

- **사람이 DocGrid 웹사이트에서 토큰을 발급/조회/폐기하는 것** → 기존 JWT 로그인을 그대로 재사용 (`/mcp/tokens`)
- **Claude Desktop이 실제 MCP 프로토콜을 호출하는 것** → 별도로 발급한 장기 API 키 (`/mcp`)

를 분리했다. `mcp_access_tokens` 테이블(V26)과 `McpAccessToken` 엔티티는 이미 존재했으므로, 이번 이슈는 그 위에 Repository/Service/Controller/필터만 새로 얹었다.

```
[시나리오 A] 사람이 웹사이트에서 토큰 관리
  브라우저 → POST/GET/DELETE /mcp/tokens (JWT) → McpTokenController → Command/QueryService

[시나리오 B] Claude Desktop이 실제 MCP 호출
  Claude Desktop → POST /mcp (API 키) → McpApiKeyAuthFilter → SecurityContext에 userId 저장
```

JWT는 시나리오 A(발급 과정)에만 관여하고, 실제 MCP 프로토콜 호출(`/mcp`)에는 전혀 쓰이지 않는다.

---

## 패키지 구조 결정

| 파일 | 위치 | 근거 |
| --- | --- | --- |
| `McpAccessTokenRepository` | `domain/user/repository` | `McpAccessToken` 엔티티가 이미 `domain/user/entity`에 있음. 이 프로젝트는 Repository를 엔티티와 같은 도메인에 두는 게 원칙(`UserRepository`/`RoleRepository`도 동일). 예외(`VectorSearchRepository`)는 특수 목적 네이티브 쿼리 전용이라 이 경우엔 해당 안 됨. |
| Service/Controller/Filter/DTO/Converter | `domain/mcp/*` | MCP 블록이 소유하는 어댑터 로직. Issue 1에서 만든 `domain/mcp/tool`과 같은 계층. |

`service-pattern.md`의 CQRS 원칙에 따라 이슈 To-do의 "McpAccessTokenService" 하나를 `McpAccessTokenCommandService`(발급/폐기/인증)와 `McpAccessTokenQueryService`(목록 조회)로 분리했다.

---

## 신규 파일

### McpAccessTokenRepository

```java
public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Long> {
    Optional<McpAccessToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);
    List<McpAccessToken> findAllByUser_IdOrderByCreatedAtDesc(Long userId);
}
```

첫 번째 메서드는 API 키 인증(해시 대조), 두 번째는 웹 토큰 목록 조회에 쓰인다.

---

### McpAccessTokenCommandService — 핵심 로직

```java
public McpAccessTokenIssueResponse issue(Long userId) {
    User user = userRepository.getReferenceById(userId);
    String rawToken = generateRawToken();          // "docgrid_mcp_" + 32바이트 랜덤(Base64 URL-safe)

    McpAccessToken token = McpAccessToken.builder()
            .user(user)
            .tokenHash(hash(rawToken))              // SHA-256 해시만 저장, 원본은 저장 안 함
            .build();
    mcpAccessTokenRepository.save(token);

    return mcpAccessTokenConverter.toIssueResponse(token, rawToken);  // 원본은 이 응답에서만 노출
}

public McpAccessTokenRevokeResponse revoke(Long userId, Long tokenId) {
    McpAccessToken token = mcpAccessTokenRepository.findById(tokenId)
            .orElseThrow(() -> new DocGridException(ErrorCode.NOT_FOUND));

    if (!token.getUser().getId().equals(userId)) {
        throw new DocGridException(ErrorCode.PERMISSION_DENIED);
    }
    if (!token.isRevoked()) {
        token.revoke(LocalDateTime.now());
    }
    return mcpAccessTokenConverter.toRevokeResponse(token);   // 이미 폐기된 토큰이면 기존 값 그대로 반환(멱등)
}

public Optional<Long> authenticate(String rawToken) {
    return mcpAccessTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken))
            .map(token -> {
                token.recordUsage(LocalDateTime.now());
                return token.getUser().getId();
            });
}
```

- `getReferenceById()`: `findById()`처럼 즉시 SELECT를 날리지 않고 프록시만 만든다 — 검색 블록 설계 문서(`#56`)에서 이미 쓴 패턴과 동일한 이유(불필요한 조회 생략).
- 토큰 원본은 **어디에도 저장하지 않는다** — 비밀번호와 동일한 원칙. 발급 응답에서 1회만 노출되고 그 이후엔 해시만 남는다.
- `revoke()`는 이미 폐기된 토큰을 다시 폐기해도 예외를 던지지 않고 기존 `revokedAt`을 그대로 반환한다(멱등) — 명세서 F-MCP-10의 "이미 폐기된 토큰 재폐기 시도" 요구사항.

---

### McpApiKeyAuthFilter — `/mcp` 경로 전용 인증

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    return !MCP_ENDPOINT.equals(request.getRequestURI());   // "/mcp"가 아니면 이 필터를 건너뜀
}

@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain filterChain) throws ServletException, IOException {
    String token = resolveToken(request);
    if (StringUtils.hasText(token)) {
        mcpAccessTokenCommandService.authenticate(token).ifPresent(userId -> {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken("mcp-client", null, List.of());
            authentication.setDetails(userId);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
    }
    filterChain.doFilter(request, response);
}
```

기존 `JwtAuthenticationFilter`와 완전히 같은 패턴(`OncePerRequestFilter` 상속, `authentication.setDetails(userId)`로 `SecurityContext`에 저장)을 미러링했다. 다른 점은 `shouldNotFilter()`로 정확히 `/mcp` 경로에만 스코프를 제한했다는 것 — `/mcp/tokens`는 이 필터를 타지 않고 기존 `JwtAuthenticationFilter`가 처리한다.

**주의**: 인증 실패(토큰 없음/무효) 시 이 필터가 직접 401을 반환하지 않는다. `SecurityContext`를 그냥 비워둔 채 다음 필터로 넘기고, 최종 차단은 `SecurityConfig`의 `anyRequest().authenticated()`가 처리한다 — 기존 `JwtAuthenticationFilter`와 동일한 방식이라 앱 전체에서 인증 실패 처리 방식이 일관된다.

---

### SecurityConfig 변경

```diff
- // TODO: 임시 permitAll — 다음 이슈에서 McpApiKeyAuthFilter로 교체 예정
- .requestMatchers("/mcp/**").permitAll()
  .requestMatchers("/admin/**").hasRole("ADMIN")
  .anyRequest().authenticated()
  ...
  .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
+ .addFilterBefore(new McpApiKeyAuthFilter(mcpAccessTokenCommandService), UsernamePasswordAuthenticationFilter.class);
```

`/mcp/**` permitAll을 통째로 지우기만 하면 `/mcp`와 `/mcp/tokens` 둘 다 `anyRequest().authenticated()`로 떨어지고, 각자 다른 필터(API 키 필터 / JWT 필터)가 `SecurityContext`를 채워주는 구조라 경로별로 별도 매처를 추가할 필요가 없었다.

---

## API 명세

### POST /mcp/tokens — 토큰 발급

```
Authorization: Bearer {JWT}
```

```json
// 201 Created
{
  "success": true,
  "status": 201,
  "data": {
    "tokenId": 1,
    "token": "docgrid_mcp_a1b2c3d4...",
    "message": "이 값은 다시 표시되지 않습니다. 안전한 곳에 보관하세요.",
    "createdAt": "2026-08-06 10:00:00"
  }
}
```

### GET /mcp/tokens — 내 토큰 목록 조회

```json
// 200 OK
{
  "success": true,
  "status": 200,
  "data": {
    "tokens": [
      { "tokenId": 1, "createdAt": "2026-08-06 10:00:00", "lastUsedAt": null, "revokedAt": null }
    ]
  }
}
```

토큰 원본 값은 목록에 포함되지 않는다.

### DELETE /mcp/tokens/{tokenId} — 토큰 폐기

```json
// 200 OK
{ "success": true, "status": 200, "data": { "tokenId": 1, "revokedAt": "2026-08-06 10:30:00" } }
```

### 에러 케이스

| 상황 | 코드 | 처리 |
| --- | --- | --- |
| JWT 없음/만료 | `UNAUTHORIZED` (401) | 기존 `JwtAuthenticationFilter` + `anyRequest().authenticated()`가 컨트롤러 진입 전 차단 |
| 존재하지 않는 tokenId 폐기 | `NOT_FOUND` (404) | `McpAccessTokenCommandService.revoke()` |
| 다른 사용자의 토큰 폐기 시도 | `PERMISSION_DENIED` (403) | 소유자 검증 |
| 이미 폐기된 토큰 재폐기 | 없음(200, 멱등) | 기존 `revokedAt` 그대로 반환 |
| `/mcp` 요청에 API 키 없음/무효/폐기됨 | `UNAUTHORIZED` (401) | `McpApiKeyAuthFilter`가 `SecurityContext` 비워둠 → `anyRequest().authenticated()`가 차단 |

---

## 검증

- `./gradlew test`: **600개 테스트 전체 통과, 실패 0개**
- 신규 단위 테스트 12개
  - `McpAccessTokenCommandServiceTest`(7개): 해시 저장 검증(원본과 다른 값), 소유자 폐기, 미소유자 폐기 시 `PERMISSION_DENIED`, 없는 토큰 폐기 시 `NOT_FOUND`, 재폐기 멱등성, 인증 성공 시 `lastUsedAt` 갱신, 인증 실패 시 빈 값
  - `McpAccessTokenQueryServiceTest`(1개): 목록 조회
  - `McpApiKeyAuthFilterTest`(4개): 유효 토큰 시 `SecurityContext` 저장, 토큰 없음/무효 시 미저장, `/mcp`가 아닌 경로는 필터 스킵

### 부수적으로 발견하고 고친 회귀

`SecurityConfig` 생성자에 `McpAccessTokenCommandService` 의존성이 추가되면서, `@Import(SecurityConfig.class)`를 쓰는 기존 `@WebMvcTest` 2곳(`IndexingJobAdminControllerTest`, `WorkerAdminControllerTest`)의 컨텍스트 로딩이 깨졌다(`NoSuchBeanDefinitionException`). 각각 `@MockitoBean McpAccessTokenCommandService`를 추가해 해결했다 — 이 프로젝트에서 `@Import(SecurityConfig.class)` 패턴을 쓰는 테스트는 이 두 곳뿐임을 `grep`으로 확인했다.

---

## 설계 결정 요약

**principal 대신 details에 userId 저장**
`JwtAuthenticationFilter`는 `authentication.getSubject()`(email)를 principal로 쓰고 userId는 `details`에 담는데, `McpApiKeyAuthFilter`는 API 키에 email 개념이 없어 principal에 고정 문자열(`"mcp-client"`)을 넣고 userId는 동일하게 `details`에 담았다. `CurrentUserArgumentResolver`가 `getDetails()`만 보므로 동작은 동일하다.

**`authenticate()`의 소비처는 아직 없음**
`CurrentUserArgumentResolver`는 Spring MVC `@RequestMapping` 메서드에만 적용되고, `@McpTool` 메서드는 SDK가 리플렉션으로 직접 호출하는 구조라 이 리졸버를 타지 않는다. 즉 이 이슈에서 `SecurityContext`에 userId를 채워 넣긴 했지만, `DocGridMcpTools`가 그 값을 실제로 읽어 쓰는 코드는 아직 없다 — 이건 다음 이슈(search_documents 실제 구현)의 몫이다.

**Rate Limiting 미적용**
API 키 인증 자체에는 호출 빈도 제한이 없다. F-MCP-08(Rate Limiting)은 도구 3종이 모두 완성된 뒤 별도 이슈로 진행한다.

---

## 남은 이슈 / TODO

- `DocGridMcpTools`의 도구 핸들러에서 `SecurityContextHolder`로부터 userId를 꺼내 쓰는 연결부 — search_documents 이슈에서 구현
- Rate Limiting — 별도 이슈
- Claude Desktop에 실제로 발급된 키를 등록해 `/mcp` 호출이 인증되는지 end-to-end 확인 — 마지막 Claude Desktop 연동 검증 이슈에서 진행

### 다음 단계

`search_documents` 도구 실제 구현 이슈로 이어진다. `SearchFacade`를 재사용하고, 이번 이슈에서 만든 인증 흐름(`SecurityContext`의 userId)을 도구 핸들러에 연결한다.
