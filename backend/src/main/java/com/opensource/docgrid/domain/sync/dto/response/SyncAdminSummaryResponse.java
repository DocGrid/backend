package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;

/**
 * 관리자 Dashboard가 한 번에 조회하는 Outbox·Issue·Reconciliation 운영 Snapshot이다.
 */
public record SyncAdminSummaryResponse(
    LocalDateTime capturedAt,
    SyncEventSummaryResponse events,
    SyncIssueSummaryResponse issues,
    SyncReconciliationSummaryResponse reconciliation
) {
}
