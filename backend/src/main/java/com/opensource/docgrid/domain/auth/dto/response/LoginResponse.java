package com.opensource.docgrid.domain.auth.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "액세스 토큰") String accessToken,
        @Schema(description = "토큰 타입") String tokenType,
        @Schema(description = "만료 시간 (초)") long expiresIn,
        @Schema(description = "사용자 ID") Long userId,
        @Schema(description = "이메일") String email,
        @Schema(description = "역할 목록") List<String> roles
) {
    public static LoginResponse of(String accessToken, long expiresIn, Long userId, String email, List<String> roles) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, userId, email, roles);
    }
}
