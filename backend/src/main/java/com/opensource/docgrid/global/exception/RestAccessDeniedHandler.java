package com.opensource.docgrid.global.exception;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.global.common.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 인증은 됐지만 권한이 없는 요청에 403과 공통 ErrorResponse 본문을 반환한다.
 *
 * <p>Service 계층은 이미 PERMISSION_DENIED로 본문 있는 403을 주는데, SecurityConfig의
 * 경로 단위 거부만 본문 없이 나가고 있었다. 같은 의미의 응답이 형식까지 같도록 맞춘다.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        ErrorCode errorCode = ErrorCode.PERMISSION_DENIED;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode, request));
    }
}
