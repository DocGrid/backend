package com.opensource.docgrid.domain.sync.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.service.command.SyncReconciliationBatchService;
import com.opensource.docgrid.domain.sync.service.command.SyncReconciliationRunService;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.RequiredArgsConstructor;

/**
 * Reconciliation 실행 이력과 검사 Transaction을 조율하고 실패 결과를 독립적으로 보존한다.
 */
@Service
@RequiredArgsConstructor
public class SyncReconciliationOrchestrator {

    private final SyncReconciliationRunService syncReconciliationRunService;
    private final SyncReconciliationBatchService syncReconciliationBatchService;

    public SyncReconciliationBatchResult reconcileBatch(
        long startCursor,
        SyncReconciliationMode mode
    ) {
        UUID runId = syncReconciliationRunService.start(mode, startCursor);
        try {
            SyncReconciliationBatchResult result = syncReconciliationBatchService.reconcile(
                runId,
                startCursor,
                mode
            );
            syncReconciliationRunService.complete(result);
            return result;
        } catch (RuntimeException exception) {
            syncReconciliationRunService.fail(runId, diagnosticCode(exception));
            throw exception;
        }
    }

    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
