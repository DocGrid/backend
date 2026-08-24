package com.opensource.docgrid.domain.user.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MCP API 키 인증에 쓰이는 장기 액세스 토큰.
 *
 * <p>원본 토큰 값은 저장하지 않고 SHA-256 해시({@link #tokenHash})만 보관한다 — 비밀번호와
 * 동일한 원칙이다. {@link com.opensource.docgrid.global.common.entity.BaseEntity}를 상속하지
 * 않고 {@code createdAt}을 직접 관리하는 이유는 이 테이블에 {@code updated_at} 컬럼이 없어서다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "mcp_access_tokens",
        indexes = {
                @Index(name = "idx_mcp_access_tokens_user_id", columnList = "user_id")
        }
)
public class McpAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Builder
    public McpAccessToken(User user, String tokenHash) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.createdAt = LocalDateTime.now();
    }

    public void recordUsage(LocalDateTime usedAt) {
        this.lastUsedAt = usedAt;
    }

    public void revoke(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }
}
