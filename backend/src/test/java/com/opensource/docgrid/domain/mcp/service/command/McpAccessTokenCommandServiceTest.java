package com.opensource.docgrid.domain.mcp.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.mcp.converter.McpAccessTokenConverter;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenIssueResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenRevokeResponse;
import com.opensource.docgrid.domain.mcp.fixture.McpFixture;
import com.opensource.docgrid.domain.user.entity.McpAccessToken;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.McpAccessTokenRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpAccessTokenCommandService 단위 테스트")
class McpAccessTokenCommandServiceTest {

    @InjectMocks
    private McpAccessTokenCommandService mcpAccessTokenCommandService;

    @Mock
    private McpAccessTokenRepository mcpAccessTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private McpAccessTokenConverter mcpAccessTokenConverter;

    @Test
    @DisplayName("정상 케이스: 토큰을 발급하면 원본 값이 아닌 해시가 저장되고 원본 값은 응답으로만 반환된다")
    void issue_savesHashedToken_andReturnsRawTokenInResponse() {
        // Given
        User user = McpFixture.createUser();
        given(userRepository.getReferenceById(McpFixture.USER_ID)).willReturn(user);
        McpAccessTokenIssueResponse expected =
                new McpAccessTokenIssueResponse(1L, "raw-token", "message", LocalDateTime.now());
        given(mcpAccessTokenConverter.toIssueResponse(any(McpAccessToken.class), any(String.class)))
                .willReturn(expected);

        // When
        McpAccessTokenIssueResponse result = mcpAccessTokenCommandService.issue(McpFixture.USER_ID);

        // Then
        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<McpAccessToken> tokenCaptor = ArgumentCaptor.forClass(McpAccessToken.class);
        then(mcpAccessTokenRepository).should(times(1)).save(tokenCaptor.capture());
        McpAccessToken saved = tokenCaptor.getValue();
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getTokenHash()).isNotBlank();

        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        then(mcpAccessTokenConverter).should(times(1))
                .toIssueResponse(any(McpAccessToken.class), rawTokenCaptor.capture());
        String rawToken = rawTokenCaptor.getValue();
        assertThat(rawToken).startsWith("docgrid_mcp_");
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken);
    }

    @Test
    @DisplayName("정상 케이스: 본인 소유 토큰을 폐기하면 revokedAt이 설정된다")
    void revoke_setsRevokedAt_whenOwner() {
        // Given
        User user = McpFixture.createUser();
        McpAccessToken token = McpFixture.createToken(user);
        given(mcpAccessTokenRepository.findById(McpFixture.TOKEN_ID)).willReturn(Optional.of(token));
        McpAccessTokenRevokeResponse expected =
                new McpAccessTokenRevokeResponse(McpFixture.TOKEN_ID, LocalDateTime.now());
        given(mcpAccessTokenConverter.toRevokeResponse(token)).willReturn(expected);

        // When
        McpAccessTokenRevokeResponse result =
                mcpAccessTokenCommandService.revoke(McpFixture.USER_ID, McpFixture.TOKEN_ID);

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("예외 케이스: 존재하지 않는 토큰을 폐기하려 하면 NOT_FOUND 예외가 발생한다")
    void revoke_throws_when_tokenNotFound() {
        given(mcpAccessTokenRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> mcpAccessTokenCommandService.revoke(McpFixture.USER_ID, 999L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("예외 케이스: 다른 사용자의 토큰을 폐기하려 하면 PERMISSION_DENIED 예외가 발생한다")
    void revoke_throws_when_notOwner() {
        User owner = McpFixture.createUser();
        McpAccessToken token = McpFixture.createToken(owner);
        given(mcpAccessTokenRepository.findById(McpFixture.TOKEN_ID)).willReturn(Optional.of(token));

        Long otherUserId = 999L;
        assertThatThrownBy(() -> mcpAccessTokenCommandService.revoke(otherUserId, McpFixture.TOKEN_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("정상 케이스: 이미 폐기된 토큰을 다시 폐기해도 예외 없이 기존 상태를 반환한다(멱등)")
    void revoke_isIdempotent_whenAlreadyRevoked() {
        User user = McpFixture.createUser();
        LocalDateTime firstRevokedAt = LocalDateTime.now().minusDays(1);
        McpAccessToken token = McpFixture.createRevokedToken(user, firstRevokedAt);
        given(mcpAccessTokenRepository.findById(McpFixture.TOKEN_ID)).willReturn(Optional.of(token));
        McpAccessTokenRevokeResponse expected =
                new McpAccessTokenRevokeResponse(McpFixture.TOKEN_ID, firstRevokedAt);
        given(mcpAccessTokenConverter.toRevokeResponse(token)).willReturn(expected);

        McpAccessTokenRevokeResponse result =
                mcpAccessTokenCommandService.revoke(McpFixture.USER_ID, McpFixture.TOKEN_ID);

        assertThat(result).isEqualTo(expected);
        assertThat(token.getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    @Test
    @DisplayName("정상 케이스: 유효한 토큰으로 인증하면 소유자 userId를 반환하고 마지막 사용 시각이 갱신된다")
    void authenticate_returnsUserId_andRecordsUsage_whenTokenValid() {
        User user = McpFixture.createUser();
        McpAccessToken token = McpFixture.createToken(user);
        given(mcpAccessTokenRepository.findByTokenHashAndRevokedAtIsNull(any(String.class)))
                .willReturn(Optional.of(token));

        Optional<Long> result = mcpAccessTokenCommandService.authenticate("raw-token");

        assertThat(result).contains(McpFixture.USER_ID);
        assertThat(token.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("예외 케이스: 존재하지 않거나 폐기된 토큰으로 인증하면 빈 값을 반환한다")
    void authenticate_returnsEmpty_whenTokenInvalid() {
        given(mcpAccessTokenRepository.findByTokenHashAndRevokedAtIsNull(any(String.class)))
                .willReturn(Optional.empty());

        Optional<Long> result = mcpAccessTokenCommandService.authenticate("invalid-token");

        assertThat(result).isEmpty();
    }
}
