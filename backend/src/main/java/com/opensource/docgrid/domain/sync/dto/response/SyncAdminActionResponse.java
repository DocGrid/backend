package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncAdminActionType;
import com.opensource.docgrid.domain.sync.enums.SyncAdminTargetType;

/**
 * 상태 변경 요청이 저장된 감사 Action 식별자와 실행자를 반환한다.
 */
public record SyncAdminActionResponse(
    UUID actionId,
    SyncAdminActionType actionType,
    SyncAdminTargetType targetType,
    String targetId,
    Long adminUserId,
    LocalDateTime occurredAt
) {
}
