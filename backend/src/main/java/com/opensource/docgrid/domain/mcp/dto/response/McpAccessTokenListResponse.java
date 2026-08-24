package com.opensource.docgrid.domain.mcp.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "MCP 토큰 목록 조회 응답")
public record McpAccessTokenListResponse(
        @Schema(description = "내 MCP 토큰 목록") List<McpAccessTokenResponse> tokens
) {
}
