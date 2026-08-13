package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.converter.SyncAdminConverter;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminActionResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncReconciliationAdminResponse;
import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAdminActionType;
import com.opensource.docgrid.domain.sync.enums.SyncAdminTargetType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.repository.SyncConsistencyIssueRepository;
import com.opensource.docgrid.domain.sync.service.SyncReconciliationOrchestrator;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 Event 재시도·Issue 복구/무시·수동 Reconciliation을 감사 Action과 함께 조율한다.
 *
 * <p>Event와 Issue 변경은 감사 행과 같은 Transaction이며, 별도 실행 이력을 가진 Reconciliation은 성공
 * 결과를 받은 뒤 독립 감사 Transaction을 기록한다.
 */
@Service
@RequiredArgsConstructor
public class SyncAdminCommandService {

    private final SyncEventManualRetryService syncEventManualRetryService;
    private final SyncConsistencyIssueRepository syncConsistencyIssueRepository;
    private final SyncEventWriter syncEventWriter;
    private final SyncReconciliationOrchestrator syncReconciliationOrchestrator;
    private final SyncAdminActionWriter syncAdminActionWriter;
    private final SyncAdminConverter syncAdminConverter;
    private final Clock clock;

    @Transactional
    public SyncAdminActionResponse retryEvent(UUID eventId, Long adminUserId) {
        // 1. FAILED Event를 즉시 Claim 가능한 Queue 상태로 되돌린다.
        syncEventManualRetryService.retry(eventId);
        // 2. 상태 변경과 같은 Transaction에 실행 관리자를 감사한다.
        return actionResponse(syncAdminActionWriter.record(
            adminUserId,
            SyncAdminActionType.EVENT_RETRIED,
            SyncAdminTargetType.SYNC_EVENT,
            eventId.toString(),
            null,
            null
        ));
    }

    @Transactional
    public SyncAdminActionResponse repairIssue(Long issueId, Long adminUserId) {
        // 1. 같은 Issue의 동시 복구 요청을 행 잠금으로 직렬화한다.
        SyncConsistencyIssue issue = findLockedIssue(issueId);
        validateRepairable(issue);
        LocalDateTime requestedAt = LocalDateTime.now(clock);

        // 2. 직접 Job을 조작하지 않고 기존 Dispatcher가 처리할 멱등 Outbox Event를 만든다.
        SyncOutboxEvent event = syncEventWriter.recordDocumentReindexRequested(
            issue.getDocumentVersion(),
            issue.getEmbeddingModel(),
            "admin:%d:issue:%d:attempt:%d".formatted(
                adminUserId,
                issue.getId(),
                issue.getRepairAttemptCount() + 1
            )
        );
        issue.markRepairing(event.getEventId(), requestedAt);

        // 3. Repair Event 식별자를 감사 Metadata로 남겨 처리 결과까지 추적할 수 있게 한다.
        return actionResponse(syncAdminActionWriter.record(
            adminUserId,
            SyncAdminActionType.ISSUE_REPAIR_REQUESTED,
            SyncAdminTargetType.CONSISTENCY_ISSUE,
            issueId.toString(),
            null,
            "{\"repairEventId\":\"%s\"}".formatted(event.getEventId())
        ));
    }

    @Transactional
    public SyncAdminActionResponse ignoreIssue(Long issueId, Long adminUserId, String reason) {
        SyncConsistencyIssue issue = findLockedIssue(issueId);
        if (issue.getStatus() != SyncConsistencyIssueStatus.OPEN) {
            throw new DocGridException(ErrorCode.SYNC_ISSUE_IGNORE_NOT_ALLOWED);
        }
        String normalizedReason = reason.trim();
        issue.ignore(LocalDateTime.now(clock), normalizedReason);
        return actionResponse(syncAdminActionWriter.record(
            adminUserId,
            SyncAdminActionType.ISSUE_IGNORED,
            SyncAdminTargetType.CONSISTENCY_ISSUE,
            issueId.toString(),
            normalizedReason,
            null
        ));
    }

    public SyncReconciliationAdminResponse reconcile(
        long cursor,
        SyncReconciliationMode mode,
        Long adminUserId
    ) {
        // Reconciler가 실행/실패 이력을 자체 Transaction으로 확정한 뒤 성공 실행만 관리자 감사에 연결한다.
        SyncReconciliationBatchResult result = syncReconciliationOrchestrator.reconcileBatch(cursor, mode);
        SyncAdminAction action = syncAdminActionWriter.record(
            adminUserId,
            SyncAdminActionType.RECONCILIATION_REQUESTED,
            SyncAdminTargetType.RECONCILIATION,
            result.runId().toString(),
            null,
            "{\"mode\":\"%s\",\"startCursor\":%d,\"endCursor\":%d}"
                .formatted(mode, result.startCursor(), result.endCursor())
        );
        return new SyncReconciliationAdminResponse(
            result.runId(),
            result.startCursor(),
            result.endCursor(),
            result.scannedCount(),
            result.detectedCount(),
            result.repairRequestedCount(),
            result.hasMore(),
            actionResponse(action)
        );
    }

    private SyncConsistencyIssue findLockedIssue(Long issueId) {
        return syncConsistencyIssueRepository.findByIdForUpdate(issueId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_ISSUE_NOT_FOUND));
    }

    private void validateRepairable(SyncConsistencyIssue issue) {
        if (issue.getStatus() != SyncConsistencyIssueStatus.OPEN
            || !issue.isRepairable()
            || issue.getDocumentVersion() == null
            || issue.getEmbeddingModel() == null) {
            throw new DocGridException(ErrorCode.SYNC_ISSUE_REPAIR_NOT_ALLOWED);
        }
    }

    private SyncAdminActionResponse actionResponse(SyncAdminAction action) {
        return syncAdminConverter.toActionResponse(action);
    }
}
