package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;

/**
 * 관리자에게 Payload와 오류 메시지를 제외하고 공개하는 Sync Event 운영 Snapshot이다.
 */
public record SyncEventAdminResponse(
    UUID eventId,
    String idempotencyKey,
    SyncAggregateType aggregateType,
    Long aggregateId,
    Long aggregateVersion,
    SyncEventType eventType,
    SyncEventStatus status,
    LocalDateTime occurredAt,
    LocalDateTime availableAt,
    LocalDateTime processedAt,
    int retryCount,
    int maxRetryCount,
    String lockedBy,
    LocalDateTime lockExpiresAt,
    String lastErrorCode
) {
}
