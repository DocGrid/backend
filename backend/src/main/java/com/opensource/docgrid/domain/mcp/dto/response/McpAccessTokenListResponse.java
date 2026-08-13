package com.opensource.docgrid.domain.mcp.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

// MCP 토큰 목록 조회 응답 DTO
public record McpAccessTokenListResponse(
        @Schema(description = "내 MCP 토큰 목록") List<McpAccessTokenResponse> tokens
) {
}
