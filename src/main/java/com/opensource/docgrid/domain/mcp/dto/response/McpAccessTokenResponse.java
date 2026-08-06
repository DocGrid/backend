package com.opensource.docgrid.domain.mcp.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

// MCP 토큰 조회 응답 DTO
public record McpAccessTokenResponse(
        @Schema(description = "토큰 ID") Long tokenId,
        @Schema(description = "발급 시각") LocalDateTime createdAt,
        @Schema(description = "마지막 사용 시각") LocalDateTime lastUsedAt,
        @Schema(description = "폐기 시각 - null이면 사용 가능") LocalDateTime revokedAt
) {
}
