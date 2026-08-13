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

    /**
     * 실행 가능한 PENDING Event에 Dispatcher Lease 소유권을 원자적으로 부여한다.
     */
    public void claim(
        String dispatcherName,
        UUID newClaimToken,
        LocalDateTime claimedAt,
        LocalDateTime newLockExpiresAt
    ) {
        // 1. 이미 Claim됐거나 끝난 Event의 소유권을 덮어쓰지 않는다.
        if (status != SyncEventStatus.PENDING || availableAt == null || availableAt.isAfter(claimedAt)) {
            throw new IllegalStateException("실행 가능한 PENDING Event만 Claim할 수 있습니다.");
        }
        // 2. 빈 소유자나 유효하지 않은 Lease가 영속화되지 않게 입력 계약을 검증한다.
        if (dispatcherName == null
            || dispatcherName.isBlank()
            || newClaimToken == null
            || claimedAt == null
            || newLockExpiresAt == null
            || !newLockExpiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException("유효한 Dispatcher 소유권과 Lease가 필요합니다.");
        }

        // 3. 처리 상태와 현재 Claim 세대의 소유권을 함께 반영한다.
        status = SyncEventStatus.PROCESSING;
        lockedBy = dispatcherName;
        claimToken = newClaimToken;
        lockExpiresAt = newLockExpiresAt;
    }

    /**
     * 현재 Claim이 Handler 부작용까지 Commit할 준비가 됐을 때 Event를 완료한다.
     */
    public void complete(UUID currentClaimToken, LocalDateTime completedAt) {
        validateActiveOwnership(currentClaimToken, completedAt);
        status = SyncEventStatus.PROCESSED;
        processedAt = completedAt;
        lastErrorCode = null;
        lastErrorMessage = null;
        clearOwnership();
    }

    /**
     * 장시간 Handler가 현재 Claim 세대를 유지한 채 Lease 만료 시각만 연장한다.
     */
    public void renewLease(
        UUID currentClaimToken,
        LocalDateTime renewedAt,
        LocalDateTime renewedLockExpiresAt
    ) {
        validateActiveOwnership(currentClaimToken, renewedAt);
        if (renewedLockExpiresAt == null || !renewedLockExpiresAt.isAfter(lockExpiresAt)) {
            throw new IllegalArgumentException("새 Lease 만료 시각은 현재 Lease보다 늦어야 합니다.");
        }
        lockExpiresAt = renewedLockExpiresAt;
    }

    /**
     * 현재 처리 실패를 기록하고 지정 시각 이후 다시 Claim 가능한 Queue 상태로 되돌린다.
     */
    public void scheduleRetry(
        UUID currentClaimToken,
        String errorCode,
        String errorMessage,
        LocalDateTime failedAt,
        LocalDateTime nextAvailableAt
    ) {
        validateActiveOwnership(currentClaimToken, failedAt);
        if (retryCount + 1 >= maxRetryCount || nextAvailableAt == null || nextAvailableAt.isBefore(failedAt)) {
            throw new IllegalStateException("남은 Retry와 다음 실행 시각이 필요합니다.");
        }
        status = SyncEventStatus.PENDING;
        retryCount++;
        availableAt = nextAvailableAt;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        clearOwnership();
    }

    /**
     * Retry를 모두 소진한 현재 Claim을 최종 실패 상태로 종결한다.
     */
    public void markFailed(
        UUID currentClaimToken,
        String errorCode,
        String errorMessage,
        LocalDateTime failedAt
    ) {
        validateActiveOwnership(currentClaimToken, failedAt);
        status = SyncEventStatus.FAILED;
        retryCount++;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        clearOwnership();
    }

    /**
     * 관리자가 최종 실패 Event에 실행 기회 한 번을 추가해 Queue로 되돌린다.
     */
    public void requeueFailed(LocalDateTime requeuedAt) {
        if (status != SyncEventStatus.FAILED || requeuedAt == null) {
            throw new IllegalStateException("FAILED Event만 수동 재처리할 수 있습니다.");
        }
        status = SyncEventStatus.PENDING;
        availableAt = requeuedAt;
        maxRetryCount++;
        lastErrorCode = null;
        lastErrorMessage = null;
        processedAt = null;
        clearOwnership();
    }

    /**
     * 만료된 PROCESSING Lease를 Retry Queue 또는 최종 실패 상태로 회수한다.
     */
    public void recoverExpiredLease(
        String errorCode,
        String errorMessage,
        LocalDateTime recoveredAt,
        LocalDateTime nextAvailableAt
    ) {
        // 1. 아직 유효하거나 이미 다른 흐름이 끝낸 Event를 오래된 복구 Snapshot으로 변경하지 않는다.
        if (status != SyncEventStatus.PROCESSING
            || claimToken == null
            || lockExpiresAt == null
            || recoveredAt == null
            || lockExpiresAt.isAfter(recoveredAt)) {
            throw new IllegalStateException("만료된 PROCESSING Event만 회수할 수 있습니다.");
        }

        // 2. 이번 만료를 실패 횟수에 반영하고 남은 기회에 따라 Queue 또는 최종 실패로 전환한다.
        retryCount++;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        if (retryCount >= maxRetryCount) {
            status = SyncEventStatus.FAILED;
        } else {
            if (nextAvailableAt == null || nextAvailableAt.isBefore(recoveredAt)) {
                throw new IllegalArgumentException("다음 실행 시각은 복구 시각보다 빠를 수 없습니다.");
            }
            status = SyncEventStatus.PENDING;
            availableAt = nextAvailableAt;
        }
        clearOwnership();
    }

    private void validateActiveOwnership(UUID currentClaimToken, LocalDateTime operatedAt) {
        if (status != SyncEventStatus.PROCESSING
            || currentClaimToken == null
            || !currentClaimToken.equals(claimToken)
            || operatedAt == null
            || lockExpiresAt == null
            || !lockExpiresAt.isAfter(operatedAt)) {
            throw new IllegalStateException("유효한 현재 Sync Event 소유권이 필요합니다.");
        }
    }

    private void clearOwnership() {
        lockedBy = null;
        claimToken = null;
        lockExpiresAt = null;
    }
}
