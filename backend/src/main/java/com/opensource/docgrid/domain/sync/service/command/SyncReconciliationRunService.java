package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.entity.SyncReconciliationRun;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.repository.SyncReconciliationRunRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reconciliation 실행 이력을 검사 Transaction과 분리해 시작·완료·실패 상태로 보존한다.
 *
 * <p>검사 Transaction이 Rollback돼도 FAILED 실행 이력은 독립 Transaction으로 남는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SyncReconciliationRunService {

    private final SyncReconciliationRunRepository syncReconciliationRunRepository;
    private final Clock clock;

    public UUID start(SyncReconciliationMode mode, long startCursor) {
        UUID runId = UUID.randomUUID();
        syncReconciliationRunRepository.save(
            SyncReconciliationRun.builder()
                .runId(runId)
                .mode(mode)
                .startCursor(startCursor)
                .startedAt(LocalDateTime.now(clock))
                .build()
        );
        return runId;
    }

    public void complete(SyncReconciliationBatchResult result) {
        SyncReconciliationRun run = getRun(result.runId());
        run.complete(
            result.endCursor(),
            result.scannedCount(),
            result.detectedCount(),
            result.repairRequestedCount(),
            LocalDateTime.now(clock)
        );
    }

    public void fail(UUID runId, String errorCode) {
        getRun(runId).fail(errorCode, LocalDateTime.now(clock));
    }

    private SyncReconciliationRun getRun(UUID runId) {
        return syncReconciliationRunRepository.findByRunId(runId)
            .orElseThrow(() -> new IllegalStateException("Reconciliation 실행 이력을 찾을 수 없습니다."));
    }
}
