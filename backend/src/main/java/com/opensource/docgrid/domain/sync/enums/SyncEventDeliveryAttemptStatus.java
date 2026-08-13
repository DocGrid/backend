package com.opensource.docgrid.domain.sync.enums;

/**
 * 한 Sync Event Claim 세대의 실행·성공·실패 결과를 나타낸다.
 */
public enum SyncEventDeliveryAttemptStatus {
    STARTED,
    SUCCEEDED,
    FAILED
}
