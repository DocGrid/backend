package com.opensource.docgrid.domain.mcp.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenIssueResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenListResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenRevokeResponse;
import com.opensource.docgrid.domain.user.entity.McpAccessToken;

@Component
public class McpAccessTokenConverter {

    public McpAccessTokenIssueResponse toIssueResponse(McpAccessToken token, String rawToken) {
        return new McpAccessTokenIssueResponse(
                token.getId(),
                rawToken,
                "이 값은 다시 표시되지 않습니다. 안전한 곳에 보관하세요.",
                token.getCreatedAt()
        );
    }

    public McpAccessTokenResponse toResponse(McpAccessToken token) {
        return new McpAccessTokenResponse(
                token.getId(),
                token.getCreatedAt(),
                token.getLastUsedAt(),
                token.getRevokedAt()
        );
    }

    public McpAccessTokenListResponse toListResponse(List<McpAccessToken> tokens) {
        return new McpAccessTokenListResponse(tokens.stream().map(this::toResponse).toList());
    }

    public McpAccessTokenRevokeResponse toRevokeResponse(McpAccessToken token) {
        return new McpAccessTokenRevokeResponse(token.getId(), token.getRevokedAt());
    }
}
