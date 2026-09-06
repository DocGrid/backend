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

    /**
     * 한 Cursor Batch의 실행 이력과 실제 정합성 검사를 조율한다.
     *
     * @return 다음 Cursor 판단과 운영 집계에 사용할 성공 결과
     */
    public SyncReconciliationBatchResult reconcileBatch(
        long startCursor,
        SyncReconciliationMode mode
    ) {
        // 1. 검사 Transaction이 실패해도 남을 독립 실행 이력을 먼저 시작한다.
        UUID runId = syncReconciliationRunService.start(mode, startCursor);
        try {
            // 2. 실행 UUID와 Cursor를 전달해 한 Batch의 탐지·Issue·복구 Event 생성을 수행한다.
            SyncReconciliationBatchResult result = syncReconciliationBatchService.reconcile(
                runId,
                startCursor,
                mode
            );

            // 3. 성공 집계를 독립 이력에 반영한 뒤 Scheduler가 Cursor를 이동할 결과를 반환한다.
            syncReconciliationRunService.complete(result);
            return result;
        } catch (RuntimeException exception) {
            // 4. 검사 Transaction과 무관하게 실패 코드가 남도록 실행 이력을 별도로 종결하고 원본 예외를 유지한다.
            syncReconciliationRunService.fail(runId, diagnosticCode(exception));
            throw exception;
        }
    }

    /**
     * 실행 이력에 민감한 예외 메시지 대신 안정적인 도메인 오류 코드 또는 예외 유형을 저장한다.
     */
    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
