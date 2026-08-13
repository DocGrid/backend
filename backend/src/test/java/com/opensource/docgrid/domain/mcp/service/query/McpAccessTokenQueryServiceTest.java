package com.opensource.docgrid.domain.mcp.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.mcp.converter.McpAccessTokenConverter;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenListResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenResponse;
import com.opensource.docgrid.domain.mcp.fixture.McpFixture;
import com.opensource.docgrid.domain.user.entity.McpAccessToken;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.McpAccessTokenRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpAccessTokenQueryService 단위 테스트")
class McpAccessTokenQueryServiceTest {

    @InjectMocks
    private McpAccessTokenQueryService mcpAccessTokenQueryService;

    @Mock
    private McpAccessTokenRepository mcpAccessTokenRepository;

    @Mock
    private McpAccessTokenConverter mcpAccessTokenConverter;

    @Test
    @DisplayName("정상 케이스: 내 토큰 목록을 조회하면 토큰 원본 값 없이 목록이 반환된다")
    void getMyTokens_returnsListResponse() {
        // Given
        User user = McpFixture.createUser();
        McpAccessToken token = McpFixture.createToken(user);
        List<McpAccessToken> tokens = List.of(token);
        given(mcpAccessTokenRepository.findAllByUser_IdOrderByCreatedAtDesc(McpFixture.USER_ID))
                .willReturn(tokens);
        McpAccessTokenListResponse expected =
                new McpAccessTokenListResponse(List.of(
                        new McpAccessTokenResponse(McpFixture.TOKEN_ID, token.getCreatedAt(), null, null)
                ));
        given(mcpAccessTokenConverter.toListResponse(tokens)).willReturn(expected);

        // When
        McpAccessTokenListResponse result = mcpAccessTokenQueryService.getMyTokens(McpFixture.USER_ID);

        // Then
        assertThat(result).isEqualTo(expected);
        then(mcpAccessTokenRepository).should(times(1)).findAllByUser_IdOrderByCreatedAtDesc(McpFixture.USER_ID);
    }
}
