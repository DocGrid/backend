package com.opensource.docgrid.domain.sync.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도메인 변경과 같은 Transaction에서 생성되는 동기화 Outbox Event다.
 *
 * <p>역할: 문서·권한·모델 변경을 후속 Dispatcher가 유실 없이 발견할 수 있는 영속 Queue로 보존한다.
 * eventId는 전달 세대를, idempotencyKey는 같은 비즈니스 변경의 중복 생성을 식별한다. Payload는 처리
 * 힌트일 뿐이며 Handler는 aggregateType과 aggregateId로 현재 DB 상태를 다시 읽어야 한다.
 *
 * <p>경계: 인덱싱 상태를 사람이 조회하는 append-only 이력은 {@code IndexingEvent}가 담당하고, 이
 * Entity는 실제로 소비·재시도해야 하는 작업만 관리한다. Dispatcher Claim과 상태 전이는 후속 단계에서
 * 이 Entity의 Lease 필드를 이용한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "sync_outbox_events",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_sync_outbox_events_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_sync_outbox_events_idempotency_key", columnNames = "idempotency_key")
    },
    indexes = {
        @Index(name = "idx_sync_outbox_events_dispatch", columnList = "status, available_at, occurred_at, id"),
        @Index(name = "idx_sync_outbox_events_lock_expires_at", columnList = "lock_expires_at"),
        @Index(
            name = "idx_sync_outbox_events_aggregate",
            columnList = "aggregate_type, aggregate_id, aggregate_version"
        )
    }
)
public class SyncOutboxEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 30)
    private SyncAggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "aggregate_version", updatable = false)
    private Long aggregateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private SyncEventType eventType;

    // OpenSQL과 현재 Hibernate 설정의 기존 JSON 경계를 따라 JSON 문자열을 TEXT로 보존한다.
    @Column(name = "payload_json", columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncEventStatus status;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry_count", nullable = false)
    private int maxRetryCount;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "locked_by", length = 200)
    private String lockedBy;

    @Column(name = "lock_expires_at")
    private LocalDateTime lockExpiresAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Builder
    public SyncOutboxEvent(
        UUID eventId,
        String idempotencyKey,
        SyncAggregateType aggregateType,
        Long aggregateId,
        Long aggregateVersion,
        SyncEventType eventType,
        String payloadJson,
        LocalDateTime availableAt,
        LocalDateTime occurredAt,
        int maxRetryCount
    ) {
        this.eventId = eventId;
        this.idempotencyKey = idempotencyKey;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = SyncEventStatus.PENDING;
        this.availableAt = availableAt;
        this.occurredAt = occurredAt;
        this.maxRetryCount = maxRetryCount;
    }
}
