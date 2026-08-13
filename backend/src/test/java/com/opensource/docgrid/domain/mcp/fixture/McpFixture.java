package com.opensource.docgrid.domain.mcp.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.user.entity.McpAccessToken;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;

public class McpFixture {

    public static final Long USER_ID = 1L;
    public static final Long TOKEN_ID = 1L;
    public static final String TOKEN_HASH = "hashed-token-value";

    private McpFixture() {
    }

    public static User createUser() {
        User user = User.builder()
                .email("mcp-user@docgrid.com")
                .passwordHash("encoded-password")
                .name("MCP테스트유저")
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    public static McpAccessToken createToken(User user) {
        McpAccessToken token = McpAccessToken.builder()
                .user(user)
                .tokenHash(TOKEN_HASH)
                .build();
        ReflectionTestUtils.setField(token, "id", TOKEN_ID);
        return token;
    }

    public static McpAccessToken createRevokedToken(User user, LocalDateTime revokedAt) {
        McpAccessToken token = createToken(user);
        token.revoke(revokedAt);
        return token;
    }
}
