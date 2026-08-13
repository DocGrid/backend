package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox Queue의 현재 적체와 최근 24시간 처리 품질 Snapshot이다.
 */
public record SyncEventSummaryResponse(
    long pendingCount,
    long processingCount,
    long failedCount,
    Long oldestPendingAgeSeconds,
    long processedLast24hCount,
    long failedLast24hCount,
    long retriedLast24hCount,
    double successRateLast24h,
    UUID lastProcessedEventId,
    LocalDateTime lastProcessedAt
) {
}
