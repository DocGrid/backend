package com.opensource.docgrid.domain.sync.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationStatus;
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
 * Reconciliation Batch의 범위와 탐지·복구 결과를 운영 지표로 보존한다.
 *
 * <p>문서별 Issue 내용은 SyncConsistencyIssue가 담당하고, 이 Entity는 한 번의 실행 단위만 요약한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "sync_reconciliation_runs",
    uniqueConstraints = @UniqueConstraint(name = "uk_sync_reconciliation_runs_run_id", columnNames = "run_id"),
    indexes = {
        @Index(name = "idx_sync_reconciliation_runs_started_at", columnList = "started_at, id"),
        @Index(name = "idx_sync_reconciliation_runs_status", columnList = "status, started_at")
    }
)
public class SyncReconciliationRun extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SyncReconciliationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncReconciliationStatus status;

    @Column(name = "start_cursor", nullable = false, updatable = false)
    private long startCursor;

    @Column(name = "end_cursor", nullable = false)
    private long endCursor;

    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;

    @Column(name = "detected_count", nullable = false)
    private int detectedCount;

    @Column(name = "repair_requested_count", nullable = false)
    private int repairRequestedCount;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    /**
     * 지정 Cursor와 모드에서 시작한 RUNNING Reconciliation 실행 이력을 생성한다.
     */
    @Builder
    public SyncReconciliationRun(
        UUID runId,
        SyncReconciliationMode mode,
        long startCursor,
        LocalDateTime startedAt
    ) {
        this.runId = runId;
        this.mode = mode;
        this.status = SyncReconciliationStatus.RUNNING;
        this.startCursor = startCursor;
        this.endCursor = startCursor;
        this.startedAt = startedAt;
    }

    /**
     * Batch가 정상 종료한 Cursor와 검사·탐지·복구 요청 집계를 기록하고 실행을 완료한다.
     */
    public void complete(
        long completedCursor,
        int scanned,
        int detected,
        int repairRequested,
        LocalDateTime completedAt
    ) {
        // 1. 실행 상태와 다음 Batch가 이어갈 마지막 Cursor를 확정한다.
        status = SyncReconciliationStatus.COMPLETED;
        endCursor = completedCursor;

        // 2. 운영 Dashboard에 노출할 결과 집계와 완료 시각을 저장하고 과거 오류를 제거한다.
        scannedCount = scanned;
        detectedCount = detected;
        repairRequestedCount = repairRequested;
        this.completedAt = completedAt;
        errorCode = null;
    }

    /**
     * 실행 중 발생한 제한된 오류 코드와 종결 시각을 기록하고 FAILED로 종료한다.
     */
    public void fail(String errorCode, LocalDateTime failedAt) {
        status = SyncReconciliationStatus.FAILED;
        this.errorCode = errorCode;
        completedAt = failedAt;
    }
}
