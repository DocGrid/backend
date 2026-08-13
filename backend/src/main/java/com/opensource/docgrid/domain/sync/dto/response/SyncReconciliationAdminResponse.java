package com.opensource.docgrid.domain.sync.dto.response;

import java.util.UUID;

/**
 * 수동 Reconciliation Batch 결과와 이를 추적할 감사 Action을 함께 반환한다.
 */
public record SyncReconciliationAdminResponse(
    UUID runId,
    long startCursor,
    long endCursor,
    int scannedCount,
    int detectedCount,
    int repairRequestedCount,
    boolean hasMore,
    SyncAdminActionResponse action
) {
}
