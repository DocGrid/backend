package com.opensource.docgrid.domain.mcp.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "MCP 토큰 발급 응답")
public record McpAccessTokenIssueResponse(
        @Schema(description = "토큰 ID") Long tokenId,
        @Schema(description = "토큰 원본 값 - 이 응답에서만 1회 노출됨") String token,
        @Schema(description = "안내 메시지") String message,
        @Schema(description = "발급 시각") LocalDateTime createdAt
) {
}
