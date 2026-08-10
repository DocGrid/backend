# Issue #137 RAGOps Dashboard WebSocket 실시간 push 상세 설계

closes #137

## 1. 배경과 목적

이슈#134(RAGOps Dashboard 집계 지표 조회 API)는 관리자가 요청할 때마다 스냅샷을 계산해 반환하는
정적 조회만 제공한다. 이번 작업은 그 스냅샷을 상태 변경 시점에 서버가 먼저 push하는 실시간
채널을 추가한다. Polling·고정 주기 스케줄러 방식은 채택하지 않고, 상태 변경 이벤트가 발생하는
시점에만 push하는 이벤트 기반 구조로 간다.

이번 이슈는 push 인프라(STOMP endpoint, 인증·인가, 전송 계층)까지만 만든다. 실제로 언제
push를 트리거할지(재처리 클릭, Worker 상태 전이)는 후속 이슈가 이 인프라를 재사용해서 채운다.

### 1.1 성공 기준

- `/ws`(SockJS) 연결과 `/topic/dashboard` 구독이 정상 동작한다.
- 상태 변경 시점에만 push하며, 고정 주기 폴링을 쓰지 않는다.
- ADMIN이 아닌 사용자는 연결 또는 구독 시점에 거부된다.
- 이 앱의 JWT 인증(stateless, 세션 없음) 모델과 충돌 없이 동작한다.

## 2. 범위

### 2.1 포함

- STOMP endpoint(`/ws`)와 Message Broker(`/topic`) 설정
- WebSocket 세션에 대한 JWT 인증(CONNECT 시점) 및 목적지별 인가(SUBSCRIBE 시점)
- `DashboardWebSocketController.sendDashboardUpdate()` — 수동 트리거 기반 push 전송 계층
- 단위·통합 테스트

### 2.2 제외

- 실제 push 트리거 시점(재처리 API, Worker 상태 전이 이벤트 훅) — 후속 이슈
- FAILED 작업 목록 조회, 재처리 API — 후속 이슈
- Dashboard 화면(Frontend)

## 3. 프로토콜 계약

```
WebSocket 연결: /ws (SockJS)
구독 채널: /topic/dashboard
```

- HTTP 핸드셰이크(`/ws/**`)는 인증하지 않는다(permitAll). 인증·인가는 STOMP 레이어에서 처리한다.
- STOMP endpoint의 허용 origin은 `CorsConfig.ALLOWED_ORIGINS`를 재사용한다(REST API와 동일 목록).

## 4. 인증·인가 설계

### 4.1 왜 HTTP 핸드셰이크가 아니라 STOMP 프레임에서 인증하는가

이 앱의 JWT 인증은 `Authorization` HTTP 헤더 기반이다. 그런데 SockJS가 `websocket` Transport로
직접 연결하면 브라우저 네이티브 `WebSocket()`은 Upgrade 요청에 커스텀 헤더를 실을 수 없다.
`xhr-streaming`/`xhr-polling` 폴백에서는 되고 `websocket` Transport에서는 안 되는, Transport
종류에 따라 인증 여부가 갈리는 상태가 된다.

그래서 인증을 HTTP 핸드셰이크가 아니라 **STOMP CONNECT 프레임**으로 옮겼다. STOMP 프로토콜은
Transport와 무관하게 항상 커스텀 헤더(native header)를 실을 수 있다. `SecurityConfig`는
`/ws/**`를 `permitAll()`로 열어 HTTP 레벨 인증을 하지 않고, 대신:

- **CONNECT 시점 인증** — `StompAuthChannelInterceptor`가 CONNECT 프레임의 `Authorization`
  헤더로 JWT를 검증하고, 유효하면 WebSocket 세션에 `Authentication`을 부착한다(`accessor.setUser()`).
  토큰이 없거나 무효하면 그 자리에서 연결을 끊는다.
- **SUBSCRIBE 시점 인가** — `DashboardSubscriptionAuthorizationInterceptor`가 `/topic/dashboard`
  구독 요청마다 세션에 부착된 Principal이 `ROLE_ADMIN`인지 다시 확인한다. CONNECT 검증 하나에만
  의존하지 않는 이중 방어다.

### 4.2 `@EnableWebSocketSecurity`를 채택하지 않은 이유

원래는 SUBSCRIBE 인가를 Spring Security의 `@EnableWebSocketSecurity`(`MessageMatcherDelegatingAuthorizationManager`
DSL)로 구현하려 했다. 그런데 실제로 붙여보니 유효한 ADMIN 토큰으로도 모든 CONNECT가
`MissingCsrfTokenException`으로 거부됐다.

원인을 바이트코드 레벨까지 확인한 결과, `@EnableWebSocketSecurity`는 STOMP endpoint(`stompWebSocketHandlerMapping`
빈)가 등록된 걸 감지하면 `CsrfChannelInterceptor`를 무조건 함께 등록한다. 이 인터셉터는
CONNECT 프레임 처리 시 세션에 저장된 `CsrfToken`이 있는지를 확인하는데, 이 앱은
`SessionCreationPolicy.STATELESS` + HTTP CSRF 비활성화 상태라 세션 자체가 없어 `CsrfToken`이
존재할 수 없다. 즉 ADMIN 여부와 무관하게 모든 CONNECT가 거부되는 구조였다. 이 버전(Spring
Security 6.5.11)에는 이 CSRF 요구를 끄는 공개 API가 없어서, `@EnableWebSocketSecurity` 자체를
쓰지 않고 `DashboardSubscriptionAuthorizationInterceptor`를 순수 `ChannelInterceptor`로 직접
구현했다. 그 결과 `spring-security-messaging` 의존성도 필요 없어져서 제외했다.

### 4.3 구현 시 발견한 버그: `StompHeaderAccessor.wrap()` vs `MessageHeaderAccessor.getAccessor()`

`StompAuthChannelInterceptor`에서 처음엔 `StompHeaderAccessor.wrap(message)`로 Accessor를
가져와 `setUser()`를 호출했는데, `wrap()`은 검증 전용 복사본을 만들 뿐이라 그 위에서 호출한
`setUser()` 결과가 반환되는 `message`에 반영되지 않았다. 그 결과 SUBSCRIBE 단계에서
`accessor.getUser()`가 항상 `null`이었다. `StompSubProtocolHandler`가 `leaveMutable(true)`로
캐시해 둔 원본 Accessor를 가져오는 `MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)`로
바꿔서 해결했다. 실제 STOMP Client로 통합 테스트를 돌리기 전까지는 드러나지 않던 문제였다.

## 5. 구현 상세

읽는 순서는 설정(5.1~5.3) → 인증(5.4) → 인가(5.5) → HTTP 레벨 배선(5.6) → 실제 push 전송(5.7)
순서를 따른다. 각 파일이 이전 파일의 결과를 어떻게 이어받는지가 핵심이다.

### 5.1 `build.gradle` — WebSocket 의존성 추가

```diff
 	implementation 'org.springframework.boot:spring-boot-starter-security'
 	implementation 'org.springframework.boot:spring-boot-starter-validation'
 	implementation 'org.springframework.boot:spring-boot-starter-web'
+	implementation 'org.springframework.boot:spring-boot-starter-websocket'
 	implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
```

한 줄 추가. 이게 없으면 `@EnableWebSocketMessageBroker`, `StompHeaderAccessor`, `SimpMessagingTemplate`
등 이 이슈에서 쓰는 클래스가 클래스패스에 없어서 5.3부터 전부 컴파일이 안 된다. 프로젝트에
WebSocket 관련 코드가 지금까지 전혀 없었기 때문에 순수 신규 추가다.

`spring-security-messaging`은 한때 추가했다가 뺐다 — SUBSCRIBE 인가를 Spring Security의
`@EnableWebSocketSecurity` DSL로 구현하려고 추가했었는데, 4.2절에서 설명하는 CSRF 문제 때문에
그 방식을 버리면서 이 의존성도 같이 뺐다. 지금 `build.gradle`엔 흔적이 없다.

### 5.2 `CorsConfig.java` — origin 목록을 WebSocket 설정과 공유

```diff
 @Configuration
 public class CorsConfig implements WebMvcConfigurer {

-    private static final List<String> ALLOWED_ORIGINS = List.of(
+    // WebSocketConfig가 STOMP endpoint 허용 origin으로 재사용하므로 package-private으로 둔다.
+    static final List<String> ALLOWED_ORIGINS = List.of(
         "http://localhost:3000",
         "http://localhost:8080"
     );
```

값은 안 바꾸고 접근 제어자만 `private` → package-private(제어자 없음)으로 넓혔다. REST API의
CORS 설정과 STOMP endpoint의 origin 허용 설정은 스프링에서 서로 완전히 다른 설정 지점이라,
`WebSocketConfig`도 `/ws` 연결을 허용할 origin이 따로 필요하다. 이걸 또 하드코딩하면 값이
두 파일에 중복돼서 배포 도메인이 바뀔 때 한쪽만 고치고 잊어버리는 사고가 날 수 있다. 같은
패키지(`global.config`)에서만 쓰면 되니 `public`까지는 열지 않았다.

### 5.3 `WebSocketConfig.java` — endpoint·broker·인터셉터 배선

```java
@EnableWebSocketMessageBroker
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final DashboardSubscriptionAuthorizationInterceptor dashboardSubscriptionAuthorizationInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(CorsConfig.ALLOWED_ORIGINS.toArray(new String[0]))
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor, dashboardSubscriptionAuthorizationInterceptor);
    }
}
```

- `@EnableWebSocketMessageBroker` — STOMP 메시지 브로커 기능 자체를 켜는 스위치. 없으면 아래
  콜백 메서드들이 호출되지 않는다.
- `registerStompEndpoints` — `/ws`로 연결 URL을 등록하고, 5.2에서 공유받은 origin 목록으로
  제한한다. `.withSockJS()`는 순수 WebSocket이 막히는 환경(오래된 브라우저, 프록시)에서 HTTP
  폴링으로 자동 폴백해주는 옵션이다.
- `configureMessageBroker` — `/topic`으로 시작하는 목적지(`/topic/dashboard`)는 스프링 내장
  in-memory 브로커가 관리한다. 클라이언트가 구독해두면 서버가 `SimpMessagingTemplate`으로 보낸
  메시지를 브로커가 구독자 전원에게 뿌린다(5.7). 클라이언트→서버 방향 메시지가 없어서
  `setApplicationDestinationPrefixes("/app")`는 넣지 않았다.
- `configureClientInboundChannel` — 클라이언트가 보내는 모든 STOMP 프레임이 지나가는 파이프에
  두 인터셉터를 순서대로 건다. **순서가 중요하다** — CONNECT 인증(5.4)이 먼저 Principal을
  세션에 부착해야, SUBSCRIBE 인가(5.5)가 그 Principal을 보고 판단할 수 있다.

### 5.4 `StompAuthChannelInterceptor.java` — CONNECT 시점 JWT 인증

```java
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    @SuppressWarnings("unchecked")
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = resolveToken(accessor);
            Claims claims = token == null ? null : jwtProvider.getClaimsIfValid(token);
            if (claims == null) {
                throw new AccessDeniedException("유효하지 않은 인증 정보입니다.");
            }

            String email = claims.getSubject();
            Long userId = claims.get("userId", Long.class);
            List<String> roles = (List<String>) claims.get("roles");
            List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(email, null, authorities);
            authentication.setDetails(userId);
            accessor.setUser(authentication);
        }

        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
```

WebSocket 자체는 그냥 양방향 파이프고 "이게 연결 요청인지 구독인지" 같은 구조가 없다. STOMP가
그 위에 CONNECT·SUBSCRIBE·SEND 같은 프레임 타입을 정의해주는데, 클라이언트는 파이프가 열리면
규격상 반드시 CONNECT를 제일 먼저 보내야 한다. 이 클래스는 그 첫 CONNECT 프레임을 가로채는
문지기다.

- `MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)` — 이 메시지를 만들
  때 `StompSubProtocolHandler`가 `leaveMutable(true)`로 캐시해 둔 **원본** Accessor를 가져온다.
  `StompHeaderAccessor.wrap(message)`를 쓰면 검증 전용 **복사본**이 생겨서, 그 위에 `setUser()`를
  호출해도 반환되는 `message`엔 반영되지 않고 조용히 사라진다 — 실제로 이렇게 짰다가 SUBSCRIBE
  단계에서 Principal이 매번 `null`로 나오는 버그를 냈다(4.3절).
- `resolveToken` — STOMP native header `Authorization: Bearer {token}`에서 토큰만 뽑는다.
  `JwtAuthenticationFilter.resolveToken()`이 HTTP 헤더에서 하는 것과 동일한 규칙을, HTTP 헤더
  대신 STOMP 프레임에서 하는 것만 다르다.
- `jwtProvider.getClaimsIfValid(token)` — 새 검증 로직이 아니라 기존 `JwtProvider`를 그대로
  재사용한다. `null`이면(토큰 없음 또는 서명·만료 검증 실패) `AccessDeniedException`을 던져
  연결을 즉시 끊는다.
- 유효하면 클레임에서 이메일·userId·역할을 꺼내 `Authentication`으로 조립하고
  `accessor.setUser(authentication)`으로 **세션에** 붙인다. HTTP는 요청 하나로 끝나 매번
  `SecurityContextHolder`를 새로 채우지만, WebSocket은 연결이 오래 유지되는 세션이라 이렇게
  세션 레벨에 신원을 붙여두고 이후 모든 프레임에서 재사용한다.

### 5.5 `DashboardSubscriptionAuthorizationInterceptor.java` — SUBSCRIBE 시점 ROLE_ADMIN 인가

```java
@Component
public class DashboardSubscriptionAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null
            && StompCommand.SUBSCRIBE.equals(accessor.getCommand())
            && DASHBOARD_TOPIC.equals(accessor.getDestination())
            && !isAdmin(accessor.getUser())) {
            throw new AccessDeniedException("대시보드 구독 권한이 없습니다.");
        }

        return message;
    }

    private boolean isAdmin(Principal user) {
        if (!(user instanceof Authentication authentication)) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
```

5.4가 "누구냐"를 확인했다면, 이 클래스는 "이 사람이 대시보드를 볼 자격이 있냐"만 본다. 5.4를
통과해서 이미 세션이 열려있는(=로그인 확인된) 사람 중에서, `/topic/dashboard`를 구독하려 할
때 ADMIN인지만 한 번 더 확인한다 — CONNECT 검증 하나에만 의존하지 않는 이중 방어다.

`preSend`의 `if` 조건 4개가 전부 참이어야 거부한다: ① Accessor가 null이 아니고 ② SUBSCRIBE
프레임이고 ③ 목적지가 `/topic/dashboard`고 ④ `isAdmin()`이 `false`일 때. 넷 중 하나라도 아니면
그냥 통과시킨다.

`isAdmin`은 `accessor.getUser()`(타입은 `java.security.Principal`)를 `Authentication`으로
다운캐스트해서 `getAuthorities()`를 봐야 권한 목록에 접근할 수 있다(`Principal` 자체는 이름만
보장하고 권한 정보가 없다). `instanceof Authentication authentication` 패턴 매칭이 실패하면
(이론상 CONNECT를 안 거친 경우) 그냥 "ADMIN 아님"으로 처리한다 — 애매하면 막는 fail-closed
방향이다.

**원래 계획과 다르게 이 클래스가 존재하는 이유**는 4.2절 참고 — Spring Security의
`@EnableWebSocketSecurity` DSL을 쓰려다 CSRF 문제로 포기하고 손으로 짠 결과물이다.

### 5.6 `SecurityConfig.java` — `/ws/**`를 HTTP 레벨에서 열어둠

```diff
                 .requestMatchers("/auth/signup", "/auth/login").permitAll()
+                // WebSocket 핸드셰이크는 여기서 인증하지 않는다. 네이티브 websocket Transport는
+                // Upgrade 요청에 커스텀 헤더를 실을 수 없어, 인증은 StompAuthChannelInterceptor가
+                // STOMP CONNECT 프레임에서 담당하고 목적지별 인가는 DashboardSubscriptionAuthorizationInterceptor가 담당한다.
+                .requestMatchers("/ws/**").permitAll()
                 .requestMatchers("/admin/**").hasRole("ADMIN")
                 .anyRequest().authenticated()
```

`authorizeHttpRequests`는 위에서부터 순서대로 매칭되는 규칙 목록이다. `/ws/**`(WebSocket 연결을
위한 HTTP Upgrade 요청)를 `.anyRequest().authenticated()`보다 **반드시 먼저** `permitAll()`로
열어둬야 한다. 안 그러면 `Authorization` 헤더 없이 오는 최초 Upgrade 요청(네이티브 `websocket`
Transport의 경우 헤더를 아예 못 붙임)이 STOMP 프로토콜이 시작되기도 전에 401로 튕겨서, 5.4·5.5가
아무리 잘 만들어져 있어도 도달할 기회 자체가 없다.

이 앱은 `.csrf(AbstractHttpConfigurer::disable)` + `SessionCreationPolicy.STATELESS`라 세션도
CSRF 토큰도 없는 상태인데, 이게 4.2절에서 설명하는 CSRF 버그의 근본 원인이기도 하다.

### 5.7 `DashboardWebSocketController.java` — 실제 push 전송

```java
@Component
@RequiredArgsConstructor
public class DashboardWebSocketController {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";

    private final SimpMessagingTemplate messagingTemplate;

    public void sendDashboardUpdate(DashboardSummaryResponse summary) {
        messagingTemplate.convertAndSend(DASHBOARD_TOPIC, summary);
    }
}
```

지금까지 5.3~5.6은 전부 "누가 연결·구독할 수 있는지"를 정하는 문지기였고, 이 클래스가 처음으로
실제 데이터를 내보낸다. `convertAndSend("/topic/dashboard", summary)`를 호출하면 `summary`를
JSON으로 직렬화해서 STOMP MESSAGE 프레임을 만들고, 5.3에서 켜둔 broker가 그 목적지를 구독 중인
세션 전원(5.4·5.5를 통과한 ADMIN들)에게 뿌린다.

`@RestController`가 아니라 `@Component`다 — HTTP로 직접 호출되는 엔드포인트가 아니라 다른
서비스 코드가 자바 메서드처럼 호출하는 빈이다. 이름은 스펙 문서(F-OPS-02)의 명명을 그대로
따랐다.

이 메서드는 `DashboardQueryService`를 직접 호출해서 스스로 집계하지 않고, 최신 스냅샷을 파라미터로
**받기만** 한다. 재처리 트리거·Worker 상태 전이 이벤트 등 호출하는 곳마다 "언제 다시 집계할지"
타이밍이 다르기 때문에, 이 클래스는 판단 없이 "받은 걸 보낸다"만 하는 얇은 전송 계층으로 남겨뒀다.

**이번 이슈 시점에는 프로덕션 코드 어디에서도 이 메서드를 호출하지 않는다.** 통합 테스트(6.2절)가
수동으로 호출해 push 자체가 동작하는지만 검증하고, 실제 호출 시점은 후속 이슈(재처리 트리거,
Worker 상태 전이 이벤트 훅)에서 채운다.

## 6. 테스트 설계

### 6.1 단위 테스트 — `StompAuthChannelInterceptorTest`

- 유효한 ADMIN 토큰 → CONNECT 프레임에 Principal 부착
- `Authorization` 헤더 없음 → 거부
- 토큰 무효 → 거부
- CONNECT가 아닌 프레임 → 검증 없이 통과, `JwtProvider` 호출 안 됨

### 6.2 통합 테스트 — `DashboardWebSocketIntegrationTest`

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + 실제 `WebSocketStompClient`로 서버에 직접
연결해서 검증한다.

- ADMIN 토큰으로 구독 → `sendDashboardUpdate()` 호출 시 1초 내 수신
- 토큰 없이 CONNECT → 연결 거부 (Transport 레벨 실패 또는 STOMP ERROR 프레임 중 어느 쪽이든 대응)
- USER(비 ADMIN) 토큰 → CONNECT는 성공하지만 `/topic/dashboard` SUBSCRIBE는 거부

## 7. 오류 계약

| 상황 | 처리 |
|---|---|
| `Authorization` 헤더 없이 CONNECT | `StompAuthChannelInterceptor`가 `AccessDeniedException` → 연결 종료 |
| 토큰 만료·서명 무효 | 위와 동일 |
| ADMIN이 아닌 사용자가 `/topic/dashboard` SUBSCRIBE | `DashboardSubscriptionAuthorizationInterceptor`가 `AccessDeniedException` → 구독 거부, 세션 종료 |
| `/ws/**` HTTP 핸드셰이크 자체 | 항상 permitAll, 여기서는 거부되지 않음 |

## 8. 커밋 분할

1. `feat: #137 WebSocket 의존성 및 STOMP endpoint 설정 추가`
2. `feat: #137 STOMP CONNECT JWT 인증 Interceptor 추가`
3. `feat: #137 대시보드 SUBSCRIBE ROLE_ADMIN 인가 Interceptor 추가`
4. `feat: #137 대시보드 WebSocket push 전송 계층 추가`
5. `test: #137 WebSocket 인증·인가·push 단위·통합 테스트 추가`

## 9. 완료 조건

- `/ws` 연결 및 `/topic/dashboard` 구독이 정상 동작한다
- `sendDashboardUpdate()` 호출 시 구독 중인 클라이언트가 1초 내 최신 지표를 수신한다
- 토큰 없음/무효 토큰으로 CONNECT 시 거부된다
- ADMIN이 아닌 사용자는 `/topic/dashboard` SUBSCRIBE가 거부된다
- 전체 빌드(`./gradlew build`)가 회귀 없이 통과한다 (733개 테스트, failures 0, errors 0)
