package com.opensource.docgrid.domain.sync.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncEventDeliveryAttemptStatus;
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
 * Dispatcher Claim Token별 처리 결과와 장애 원인을 보존하는 append-only 실행 이력이다.
 *
 * <p>현재 Queue 상태는 {@link SyncOutboxEvent}가 담당하고, 이 Entity는 성공 후 Event의 오류 Snapshot이
 * 정리돼도 과거 Handler 실패와 Lease 만료 원인을 운영자가 추적할 수 있게 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "sync_event_delivery_attempts",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_sync_event_delivery_attempts_claim_token",
            columnNames = "claim_token"
        ),
        @UniqueConstraint(
            name = "uk_sync_event_delivery_attempts_event_attempt",
            columnNames = {"event_id", "attempt_no"}
        )
    },
    indexes = {
        @Index(name = "idx_sync_event_delivery_attempts_event_started", columnList = "event_id, started_at"),
        @Index(name = "idx_sync_event_delivery_attempts_status_started", columnList = "status, started_at")
    }
)
public class SyncEventDeliveryAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "claim_token", nullable = false, updatable = false)
    private UUID claimToken;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "dispatcher_name", nullable = false, updatable = false, length = 200)
    private String dispatcherName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncEventDeliveryAttemptStatus status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Builder
    public SyncEventDeliveryAttempt(
        UUID eventId,
        UUID claimToken,
        int attemptNo,
        String dispatcherName,
        LocalDateTime startedAt
    ) {
        if (eventId == null
            || claimToken == null
            || attemptNo <= 0
            || dispatcherName == null
            || dispatcherName.isBlank()
            || startedAt == null) {
            throw new IllegalArgumentException("유효한 Event Claim 실행 정보가 필요합니다.");
        }
        this.eventId = eventId;
        this.claimToken = claimToken;
        this.attemptNo = attemptNo;
        this.dispatcherName = dispatcherName;
        this.status = SyncEventDeliveryAttemptStatus.STARTED;
        this.startedAt = startedAt;
    }

    /**
     * Handler 부작용과 Event 완료가 Commit될 때 현재 실행을 성공으로 종결한다.
     */
    public void succeed(LocalDateTime succeededAt) {
        validateStarted(succeededAt);
        status = SyncEventDeliveryAttemptStatus.SUCCEEDED;
        completedAt = succeededAt;
        errorCode = null;
        errorMessage = null;
    }

    /**
     * Handler 실패나 Lease 만료 원인을 보존하고 현재 실행을 실패로 종결한다.
     */
    public void fail(String failureCode, String failureMessage, LocalDateTime failedAt) {
        validateStarted(failedAt);
        if (failureCode == null
            || failureCode.isBlank()
            || failureMessage == null
            || failureMessage.isBlank()) {
            throw new IllegalArgumentException("실패 Attempt에는 오류 코드와 안전한 진단 문구가 필요합니다.");
        }
        status = SyncEventDeliveryAttemptStatus.FAILED;
        completedAt = failedAt;
        errorCode = failureCode;
        errorMessage = failureMessage;
    }

    private void validateStarted(LocalDateTime completedAt) {
        if (status != SyncEventDeliveryAttemptStatus.STARTED
            || completedAt == null
            || startedAt == null
            || completedAt.isBefore(startedAt)) {
            throw new IllegalStateException("STARTED Sync Event Attempt만 유효한 시각으로 종결할 수 있습니다.");
        }
    }
}
