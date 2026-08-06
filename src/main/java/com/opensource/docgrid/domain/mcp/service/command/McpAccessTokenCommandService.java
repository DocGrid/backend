package com.opensource.docgrid.domain.mcp.service.command;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.mcp.converter.McpAccessTokenConverter;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenIssueResponse;
import com.opensource.docgrid.domain.mcp.dto.response.McpAccessTokenRevokeResponse;
import com.opensource.docgrid.domain.user.entity.McpAccessToken;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.McpAccessTokenRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class McpAccessTokenCommandService {

    private static final String TOKEN_PREFIX = "docgrid_mcp_";
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final McpAccessTokenRepository mcpAccessTokenRepository;
    private final UserRepository userRepository;
    private final McpAccessTokenConverter mcpAccessTokenConverter;

    // 토큰 발급
    public McpAccessTokenIssueResponse issue(Long userId) {
        // 1. 소유자 조회 — FK 연결용 프록시만 필요하므로 SELECT 없는 getReferenceById 사용
        User user = userRepository.getReferenceById(userId);

        // 2. 원본 토큰 생성 — 이 값은 응답에서만 1회 반환되고 어디에도 저장하지 않는다
        String rawToken = generateRawToken();

        // 3. 해시만 저장 (원본 토큰은 저장하지 않음 — 비밀번호와 동일한 원칙)
        McpAccessToken token = McpAccessToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .build();
        mcpAccessTokenRepository.save(token);

        // 4. 원본 토큰을 포함한 응답 조립 — 이후로는 원본 토큰을 다시 조회할 방법이 없음
        return mcpAccessTokenConverter.toIssueResponse(token, rawToken);
    }

    // 토큰 폐기
    public McpAccessTokenRevokeResponse revoke(Long userId, Long tokenId) {
        // 1. 토큰 존재 확인
        McpAccessToken token = mcpAccessTokenRepository.findById(tokenId)
                .orElseThrow(() -> new DocGridException(ErrorCode.NOT_FOUND));

        // 2. 소유자 검증
        if (!token.getUser().getId().equals(userId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 3. 상태 전이 — 이미 폐기된 토큰이면 재전이하지 않고 기존 상태를 그대로 반환(멱등)
        if (!token.isRevoked()) {
            token.revoke(LocalDateTime.now());
        }
        return mcpAccessTokenConverter.toRevokeResponse(token);
    }

    // 토큰 인증 (유효한 토큰이면 사용자 ID 반환, 사용 기록 업데이트)
    public Optional<Long> authenticate(String rawToken) {
        // 1. 해시 대조로 폐기되지 않은 유효한 토큰 조회
        // 2. 존재하면 마지막 사용 시각 갱신 후 소유자 userId 반환, 없으면 빈 값 반환
        return mcpAccessTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken))
                .map(token -> {
                    token.recordUsage(LocalDateTime.now());
                    return token.getUser().getId();
                });
    }

    // 토큰 생성 (32바이트 랜덤 + Base64 URL-safe 인코딩)
    private String generateRawToken() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        new SecureRandom().nextBytes(randomBytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    // 토큰 해시 (SHA-256)
    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes());
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
