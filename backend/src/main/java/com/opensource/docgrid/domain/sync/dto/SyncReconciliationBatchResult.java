package com.opensource.docgrid.domain.sync.dto;

import java.util.UUID;

/**
 * 한 Reconciliation Cursor Batch의 실행 범위와 탐지·복구 요청 집계를 전달한다.
 */
public record SyncReconciliationBatchResult(
    UUID runId,
    long startCursor,
    long endCursor,
    int scannedCount,
    int detectedCount,
    int repairRequestedCount,
    boolean hasMore
) {
}
