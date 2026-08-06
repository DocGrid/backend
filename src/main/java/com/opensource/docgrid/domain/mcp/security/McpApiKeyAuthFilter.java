package com.opensource.docgrid.domain.mcp.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/*
 * Claude Desktop 같은 MCP 클라이언트가 /mcp 경로로 도구 호출 요청을 보낼 때,
 * 헤더에 담긴 API 키(=우리가 발급한 MCP 토큰)가 유효한지 검증하는 인증 필터.
 *
 * 웹사이트 로그인은 JWT를 쓰지만, JWT는 만료 시간이 짧아(1시간) Claude Desktop처럼
 * 설정 파일에 한 번 등록해두고 계속 재사용하는 시나리오엔 맞지 않는다.
 * 그래서 별도의 장기 API 키 방식을 도입했고, 이 필터가 그 검증을 담당한다.
 *
 * 처리 흐름:
 *   1) Authorization 헤더에서 API 키(원본 토큰 문자열)를 꺼낸다.
 *   2) 그 키를 해시화해서 DB(mcp_access_tokens)와 대조해 유효성을 확인한다.
 *   3) 유효하면 그 토큰의 소유자(userId)를 알아내
 *      Spring Security의 SecurityContext에 "이 요청은 이 유저 것"이라고 등록한다.
 *   4) 이후 요청을 처리하는 모든 코드(도구 핸들러 등)가
 *      SecurityContext에서 이 userId를 꺼내 "누가 요청했는지" 알 수 있게 된다.
 *
 * OncePerRequestFilter를 상속해 요청 1개당 정확히 한 번만 실행되도록 보장하며,
 * shouldNotFilter()로 /mcp 경로에만 좁게 적용되도록 스코프를 제한한다
 * (다른 경로, 예: /mcp/tokens는 기존 JwtAuthenticationFilter가 별도로 담당).
 */
@RequiredArgsConstructor
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    // 이 필터가 감시할 유일한 경로. /mcp/tokens 같은 다른 경로는 이 필터와 무관하다.
    private static final String MCP_ENDPOINT = "/mcp";

    // 실제 토큰 검증 로직(해시 대조, DB 조회)은 여기에 위임한다 — 필터는 인증 "흐름"만 담당.
    private final McpAccessTokenCommandService mcpAccessTokenCommandService;

    // true를 반환하면 이 필터를 건너뛴다. 즉 "/mcp가 아닌 요청은 이 필터를 타지 마라"는 뜻.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MCP_ENDPOINT.equals(request.getRequestURI());
    }

    // 실제 인증 로직. shouldNotFilter가 false를 반환한 요청(=/mcp 요청)에서만 실행된다.
    @Override
    protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        // Authorization 헤더에서 "Bearer " 뒤에 붙은 실제 토큰 값만 추출
        String token = resolveToken(request);

        // 헤더에 토큰이 아예 없으면 인증 시도 자체를 스킵 (아래로 그냥 통과됨)
        if (StringUtils.hasText(token)) {

            // 토큰을 해시화해서 DB(mcp_access_tokens)와 대조 → 유효하면 userId를 담은 Optional 반환
            Optional<Long> userId = mcpAccessTokenCommandService.authenticate(token);

            // Optional이 값을 갖고 있을 때(=토큰이 유효할 때)만 아래 블록 실행
            userId.ifPresent(id -> {

                // "인증 성공했다"는 사실을 표현하는 Spring Security 객체를 생성.
                // principal 자리엔 실제 이름 대신 "mcp-client"라는 고정 문자열만 넣음
                // (JWT 필터처럼 principal에 userId를 바로 넣는 방식과는 다른 패턴이니 주의)
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken("mcp-client", null, List.of());

                // 진짜 userId는 details 필드에 별도로 저장해둔다.
                // 나중에 도구 핸들러에서 유저를 식별하려면 getPrincipal()이 아니라 getDetails()를 써야 함
                authentication.setDetails(id);

                // 이 요청이 처리되는 동안 전역적으로 접근 가능한 컨텍스트에 인증 정보를 저장
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        // 인증 성공/실패 여부와 무관하게 항상 다음 필터로 요청을 넘긴다.
        // 인증 실패(SecurityContext가 비어있음)에 대한 최종 차단은 이 필터가 아니라
        // SecurityConfig의 anyRequest().authenticated()가 처리한다. 커스텀 AuthenticationEntryPoint가
        // 없어 Spring Security 기본 동작(Http403ForbiddenEntryPoint)에 따라 403으로 응답한다 —
        // 이는 이 필터만의 동작이 아니라 앱 전체 미인증 요청에 이미 적용되는 기존 동작이다.
        filterChain.doFilter(request, response);
    }

    // "Authorization: Bearer {토큰}" 형식의 헤더에서 {토큰} 부분만 잘라내는 헬퍼
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            // "Bearer "는 정확히 7글자 → 그 뒤부터가 실제 토큰 값
            return bearer.substring(7);
        }
        return null;
    }
}