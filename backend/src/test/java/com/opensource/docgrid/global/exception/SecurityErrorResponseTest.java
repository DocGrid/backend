package com.opensource.docgrid.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Spring Security 경계에서 나가는 인증·권한 실패 응답의 상태 코드와 본문 형식을 검증한다.
 *
 * <p>기본 동작은 둘 다 본문 없는 403이라 Client가 재로그인 대상과 권한 부족을 구분할 수 없었다.
 */
@DisplayName("Security 예외 응답 단위 테스트")
class SecurityErrorResponseTest {

    // 실제 주입되는 Bean은 Spring Boot가 JavaTimeModule을 등록해 주므로 ErrorResponse.timestamp가 직렬화된다.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("인증하지 않은 요청은 401과 COMMON-007 본문을 받는다")
    void entryPoint_writesUnauthorizedBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(objectMapper)
            .commence(request, response, new BadCredentialsException("no token"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        // 한글 메시지가 깨지지 않도록 인코딩까지 고정한다.
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");

        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
        assertThat(body.get("message").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getMessage());
        assertThat(body.get("status").asInt()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("method").asText()).isEqualTo("GET");
        assertThat(body.get("path").asText()).isEqualTo("/api/documents");
    }

    @Test
    @DisplayName("권한이 없는 요청은 403과 ROLE-002 본문을 받는다")
    void accessDeniedHandler_writesForbiddenBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler(objectMapper)
            .handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());

        var body = objectMapper.readTree(response.getContentAsString());
        // Service 계층이 던지는 PERMISSION_DENIED와 같은 코드라 Client가 한 경로로 처리할 수 있다.
        assertThat(body.get("code").asText()).isEqualTo(ErrorCode.PERMISSION_DENIED.getCode());
        assertThat(body.get("status").asInt()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }
}
