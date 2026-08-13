package com.opensource.docgrid.domain.sync.enums;

/**
 * Reconciliation Batch가 탐지만 수행하는지 안전한 복구 Event까지 생성하는지 구분한다.
 */
public enum SyncReconciliationMode {
    DRY_RUN,
    REPAIR
}
