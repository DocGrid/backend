package com.opensource.docgrid.domain.sync.enums;

/**
 * 일관성 Issue의 탐지, 자동 복구, 해결과 관리자 무시 생명주기를 정의한다.
 */
public enum SyncConsistencyIssueStatus {
    OPEN,
    REPAIRING,
    RESOLVED,
    IGNORED
}
