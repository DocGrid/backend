package com.opensource.docgrid.domain.sync.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.dto.response.SyncAdminActionResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncEventAdminResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncIssueAdminResponse;
import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;

/**
 * Sync 운영 Entity를 내부 소유권·Payload·오류 메시지가 제거된 관리자 DTO로 변환한다.
 */
@Component
public class SyncAdminConverter {

    public SyncEventAdminResponse toEventResponse(SyncOutboxEvent event) {
        return new SyncEventAdminResponse(
            event.getEventId(),
            event.getIdempotencyKey(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getAggregateVersion(),
            event.getEventType(),
            event.getStatus(),
            event.getOccurredAt(),
            event.getAvailableAt(),
            event.getProcessedAt(),
            event.getRetryCount(),
            event.getMaxRetryCount(),
            event.getLockedBy(),
            event.getLockExpiresAt(),
            event.getLastErrorCode()
        );
    }

    public SyncIssueAdminResponse toIssueResponse(SyncConsistencyIssue issue) {
        return new SyncIssueAdminResponse(
            issue.getId(),
            issue.getIssueKey(),
            issue.getIssueType(),
            issue.getSeverity(),
            issue.getStatus(),
            issue.getDocument() == null ? null : issue.getDocument().getId(),
            issue.getDocumentVersion() == null ? null : issue.getDocumentVersion().getId(),
            issue.getEmbeddingModel() == null ? null : issue.getEmbeddingModel().getId(),
            issue.getExpectedJson(),
            issue.getActualJson(),
            issue.isRepairable(),
            issue.getDetectedAt(),
            issue.getLastDetectedAt(),
            issue.getRepairEventId(),
            issue.getRepairAttemptCount(),
            issue.getResolvedAt(),
            issue.getResolutionMessage()
        );
    }

    public SyncAdminActionResponse toActionResponse(SyncAdminAction action) {
        return new SyncAdminActionResponse(
            action.getActionId(),
            action.getActionType(),
            action.getTargetType(),
            action.getTargetId(),
            action.getAdminUser().getId(),
            action.getOccurredAt()
        );
    }
}
