package com.opensource.docgrid.domain.sync.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncAdminActionType;
import com.opensource.docgrid.domain.sync.enums.SyncAdminTargetType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Event 재시도·Issue 복구/무시·수동 Reconciliation을 실행한 관리자와 이유를 append-only로 보존한다.
 *
 * <p>대상 도메인 상태는 각 Entity가 담당하고 이 Entity는 누가 언제 어떤 명령을 실행했는지만 감사한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "sync_admin_actions",
    uniqueConstraints = @UniqueConstraint(name = "uk_sync_admin_actions_action_id", columnNames = "action_id"),
    indexes = {
        @Index(name = "idx_sync_admin_actions_admin_occurred", columnList = "admin_user_id, occurred_at, id"),
        @Index(name = "idx_sync_admin_actions_type_occurred", columnList = "action_type, occurred_at, id"),
        @Index(name = "idx_sync_admin_actions_target", columnList = "target_type, target_id, occurred_at")
    }
)
public class SyncAdminAction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false, updatable = false)
    private UUID actionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false, length = 50)
    private SyncAdminActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 30)
    private SyncAdminTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false, length = 100)
    private String targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_user_id", nullable = false, updatable = false)
    private User adminUser;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "metadata_json", columnDefinition = "TEXT", updatable = false)
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Builder
    public SyncAdminAction(
        UUID actionId,
        SyncAdminActionType actionType,
        SyncAdminTargetType targetType,
        String targetId,
        User adminUser,
        String reason,
        String metadataJson,
        LocalDateTime occurredAt
    ) {
        this.actionId = actionId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.adminUser = adminUser;
        this.reason = reason;
        this.metadataJson = metadataJson;
        this.occurredAt = occurredAt;
    }
}
