package com.opensource.docgrid.domain.mcp.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenIssueResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenListResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenRevokeResponse;
import com.opensource.docgrid.domain.user.entity.McpAccessToken;

/**
 * McpAccessToken Entity를 API 응답 DTO로 변환한다. 토큰 원본 값은 발급 직후 1회 응답에만
 * 담기고 Entity에는 해시만 저장되므로, 그 값은 Entity가 아니라 파라미터로 별도로 받는다.
 */
@Component
public class McpAccessTokenConverter {

    /** 발급 응답을 만든다. rawToken은 Entity에 저장되지 않아 이 응답 이후로는 다시 조회할 방법이 없다. */
    public McpAccessTokenIssueResponse toIssueResponse(McpAccessToken token, String rawToken) {
        return new McpAccessTokenIssueResponse(
                token.getId(),
                rawToken,
                "이 값은 다시 표시되지 않습니다. 안전한 곳에 보관하세요.",
                token.getCreatedAt()
        );
    }

    /** 단건 조회 응답을 만든다. Entity의 tokenHash는 옮기지 않아 원본·해시 둘 다 응답에 노출되지 않는다. */
    public McpAccessTokenResponse toResponse(McpAccessToken token) {
        return new McpAccessTokenResponse(
                token.getId(),
                token.getCreatedAt(),
                token.getLastUsedAt(),
                token.getRevokedAt()
        );
    }

    /** 목록 응답을 만든다. 필드 매핑 규칙이 두 곳에서 어긋나지 않도록 toResponse()를 그대로 재사용한다. */
    public McpAccessTokenListResponse toListResponse(List<McpAccessToken> tokens) {
        return new McpAccessTokenListResponse(tokens.stream().map(this::toResponse).toList());
    }

    /** 폐기 응답을 만든다. revoke() 처리 직후의 Entity 상태를 그대로 읽으므로 별도 재조회가 없다. */
    public McpAccessTokenRevokeResponse toRevokeResponse(McpAccessToken token) {
        return new McpAccessTokenRevokeResponse(token.getId(), token.getRevokedAt());
    }
}
