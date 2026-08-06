package com.opensource.docgrid.domain.mcp.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.mcp.converter.McpAccessTokenConverter;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenListResponse;
import com.opensource.docgrid.domain.user.repository.McpAccessTokenRepository;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class McpAccessTokenQueryService {

    private final McpAccessTokenRepository mcpAccessTokenRepository;
    private final McpAccessTokenConverter mcpAccessTokenConverter;

    // 내 토큰 목록 조회
    public McpAccessTokenListResponse getMyTokens(Long userId) {
        return mcpAccessTokenConverter.toListResponse(
                mcpAccessTokenRepository.findAllByUser_IdOrderByCreatedAtDesc(userId)
        );
    }
}
