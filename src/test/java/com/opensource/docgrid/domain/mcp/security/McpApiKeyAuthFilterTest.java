package com.opensource.docgrid.domain.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.opensource.docgrid.domain.mcp.fixture.McpFixture;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpApiKeyAuthFilter 단위 테스트")
class McpApiKeyAuthFilterTest {

    @InjectMocks
    private McpApiKeyAuthFilter mcpApiKeyAuthFilter;

    @Mock
    private McpAccessTokenCommandService mcpAccessTokenCommandService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 케이스: /mcp 요청에 유효한 API 키가 있으면 SecurityContext에 userId가 저장된다")
    void doFilter_setsSecurityContext_whenTokenValid() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        given(mcpAccessTokenCommandService.authenticate("valid-token")).willReturn(Optional.of(McpFixture.USER_ID));

        // When
        mcpApiKeyAuthFilter.doFilter(request, response, chain);

        // Then
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getDetails()).isEqualTo(McpFixture.USER_ID);
    }

    @Test
    @DisplayName("예외 케이스: /mcp 요청에 API 키가 없으면 SecurityContext가 설정되지 않는다")
    void doFilter_doesNotSetSecurityContext_whenTokenMissing() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // When
        mcpApiKeyAuthFilter.doFilter(request, response, chain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("예외 케이스: /mcp 요청에 유효하지 않은 API 키가 있으면 SecurityContext가 설정되지 않는다")
    void doFilter_doesNotSetSecurityContext_whenTokenInvalid() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        given(mcpAccessTokenCommandService.authenticate("invalid-token")).willReturn(Optional.empty());

        // When
        mcpApiKeyAuthFilter.doFilter(request, response, chain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("정상 케이스: /mcp가 아닌 경로는 필터가 동작하지 않고 그대로 통과한다")
    void doFilter_skipsAuthentication_whenPathIsNotMcpEndpoint() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/tokens");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // When
        mcpApiKeyAuthFilter.doFilter(request, response, chain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
