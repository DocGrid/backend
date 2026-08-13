package com.opensource.docgrid.domain.sync.dto.request;

import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 관리자가 실행할 Reconciliation 모드와 시작 ID Cursor를 지정한다.
 */
public record RunSyncReconciliationRequest(
    @NotNull
    SyncReconciliationMode mode,

    @PositiveOrZero
    long cursor
) {
}
