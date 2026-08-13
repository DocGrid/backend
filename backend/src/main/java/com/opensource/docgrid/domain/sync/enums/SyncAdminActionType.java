package com.opensource.docgrid.domain.sync.enums;

/**
 * 관리자가 Sync 운영 상태에 수행한 감사 대상 명령 유형이다.
 */
public enum SyncAdminActionType {
    EVENT_RETRIED,
    ISSUE_REPAIR_REQUESTED,
    ISSUE_IGNORED,
    RECONCILIATION_REQUESTED
}
