package com.opensource.docgrid.domain.sync.dto.response;

/**
 * 정합성 Issue의 활성 상태와 최근 자동 복구 결과 Snapshot이다.
 */
public record SyncIssueSummaryResponse(
    long openCount,
    long repairingCount,
    long autoResolvedLast24hCount,
    long failedRepairCount
) {
}
