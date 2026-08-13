package com.opensource.docgrid.domain.sync.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

/**
 * 관리자가 불일치 근거와 복구 생명주기를 판단하는 정합성 Issue 응답이다.
 */
public record SyncIssueAdminResponse(
    Long issueId,
    String issueKey,
    SyncConsistencyIssueType issueType,
    SyncConsistencySeverity severity,
    SyncConsistencyIssueStatus status,
    Long documentId,
    Long documentVersionId,
    Long embeddingModelId,
    String expectedJson,
    String actualJson,
    boolean repairable,
    LocalDateTime detectedAt,
    LocalDateTime lastDetectedAt,
    UUID repairEventId,
    int repairAttemptCount,
    LocalDateTime resolvedAt,
    String resolutionMessage
) {
}
