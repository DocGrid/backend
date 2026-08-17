package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;

/**
 * JWT 발급·검증 계약을 검증한다. 특히 role은 더 이상 토큰에 담기지 않는다는 계약을 고정한다.
 */
class JwtProviderTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-provider-unit-test";

    private final JwtProvider jwtProvider = new JwtProvider(TEST_SECRET, 3600L);

    @Test
    @DisplayName("토큰에는 roles claim이 없고, userId/sub/jti/expiration만 담긴다")
    void generateToken_omitsRolesClaim() {
        String token = jwtProvider.generateToken(1L, "user@test.com");

        Claims claims = jwtProvider.getClaimsIfValid(token);

        assertThat(claims).isNotNull();
        assertThat(claims.get("roles")).isNull();
        assertThat(claims.get("userId", Long.class)).isEqualTo(1L);
        assertThat(claims.getSubject()).isEqualTo("user@test.com");
        assertThat(claims.get("jti", String.class)).isNotBlank();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }
}
