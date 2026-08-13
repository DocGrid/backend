package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationStatus;

/**
 * 가장 최근 Reconciliation Batch의 범위와 탐지·복구 결과다.
 */
public record SyncReconciliationSummaryResponse(
    UUID runId,
    SyncReconciliationMode mode,
    SyncReconciliationStatus status,
    long startCursor,
    long endCursor,
    int scannedCount,
    int detectedCount,
    int repairRequestedCount,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String errorCode
) {
}
