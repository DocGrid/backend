package com.opensource.docgrid.domain.mcp.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenIssueResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenListResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenRevokeResponse;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.mcp.service.query.McpAccessTokenQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "McpToken", description = "MCP 연동 토큰(API 키) 관리 API")
@RestController
@RequestMapping("/mcp/tokens")
@RequiredArgsConstructor
public class McpTokenController {

    private final McpAccessTokenCommandService mcpAccessTokenCommandService;
    private final McpAccessTokenQueryService mcpAccessTokenQueryService;

    @Operation(
        summary = "MCP 토큰 발급",
        description = "Claude Desktop 등에 등록할 장기 API 키를 발급합니다. 토큰 원본 값은 이 응답에서만 1회 노출되며, 이후 다시 조회할 수 없습니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<McpAccessTokenIssueResponse>> issueToken(
            @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.created(mcpAccessTokenCommandService.issue(userId));
    }

    @Operation(
        summary = "내 MCP 토큰 목록 조회",
        description = "발급받은 MCP 토큰 목록을 조회합니다. 토큰 원본 값은 포함되지 않습니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<McpAccessTokenListResponse>> getMyTokens(
            @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(mcpAccessTokenQueryService.getMyTokens(userId));
    }

    @Operation(
        summary = "MCP 토큰 폐기",
        description = "지정한 토큰을 폐기합니다. 폐기 즉시 해당 토큰으로 온 MCP 요청은 차단됩니다. 이미 폐기된 토큰을 다시 요청해도 오류 없이 현재 상태를 반환합니다."
    )
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<McpAccessTokenRevokeResponse>> revokeToken(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @PathVariable Long tokenId
    ) {
        return ResponseUtils.ok(mcpAccessTokenCommandService.revoke(userId, tokenId));
    }
}
