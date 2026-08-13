package com.opensource.docgrid.domain.mcp.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

// MCP 토큰 폐기 응답 DTO
public record McpAccessTokenRevokeResponse(
        @Schema(description = "토큰 ID") Long tokenId,
        @Schema(description = "폐기 시각") LocalDateTime revokedAt
) {
}
