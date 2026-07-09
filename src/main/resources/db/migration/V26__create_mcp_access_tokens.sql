-- mcp_access_tokens: MCP API 접근 토큰 테이블
CREATE TABLE mcp_access_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX idx_mcp_access_tokens_user_id ON mcp_access_tokens (user_id);
