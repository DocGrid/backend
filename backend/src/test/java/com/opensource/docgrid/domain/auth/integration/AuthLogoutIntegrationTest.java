package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.auth.dto.request.LoginRequest;
import com.opensource.docgrid.domain.auth.dto.request.SignupRequest;
import com.opensource.docgrid.domain.auth.dto.response.LoginResponse;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.auth.service.command.AuthCommandService;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;

import io.jsonwebtoken.Claims;

/**
 * 실제 PostgreSQL·Redis에서 로그인한 토큰이 로그아웃 이후 블랙리스트에 등록되어
 * 더 이상 인증에 쓰일 수 없는 상태가 되는지 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DisplayName("로그아웃 PostgreSQL·Redis 통합 테스트")
class AuthLogoutIntegrationTest {

    @Autowired
    private AuthCommandService authCommandService;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    @DisplayName("로그인 후 로그아웃하면 발급된 토큰의 jti가 블랙리스트에 등록된다")
    void logout_blacklistsIssuedToken() {
        Long departmentId = departmentRepository.findAll().get(0).getId();
        String email = "logout-integration-" + UUID.randomUUID() + "@test.com";
        authCommandService.signup(new SignupRequest(email, "password1234", "로그아웃통합테스트", departmentId));

        LoginResponse loginResponse = authCommandService.login(new LoginRequest(email, "password1234"));
        String token = loginResponse.accessToken();
        Claims claims = jwtProvider.getClaimsIfValid(token);
        String jti = claims.get("jti", String.class);

        assertThat(tokenBlacklistService.isBlacklisted(jti)).isFalse();

        authCommandService.logout(token);

        assertThat(tokenBlacklistService.isBlacklisted(jti)).isTrue();
    }
}
