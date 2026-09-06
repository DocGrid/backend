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

    /**
     * 검사 범위의 시작 Cursor와 실행 모드를 가진 Reconciliation 실행 이력을 시작한다.
     *
     * @return 검사 Batch와 후속 완료·실패 기록을 연결할 실행 UUID
     */
    public UUID start(SyncReconciliationMode mode, long startCursor) {
        // 1. 검사 Transaction과 독립적으로 추적할 실행 식별자를 발급한다.
        UUID runId = UUID.randomUUID();

        // 2. 실행 모드와 시작 Cursor, 시작 시각을 RUNNING 이력으로 저장한다.
        syncReconciliationRunRepository.save(
            SyncReconciliationRun.builder()
                .runId(runId)
                .mode(mode)
                .startCursor(startCursor)
                .startedAt(LocalDateTime.now(clock))
                .build()
        );

        // 3. 호출자가 검사 결과에 같은 실행 식별자를 포함하도록 반환한다.
        return runId;
    }

    /**
     * 성공한 Batch의 종료 Cursor와 검사·탐지·복구 요청 집계를 실행 이력에 기록한다.
     */
    public void complete(SyncReconciliationBatchResult result) {
        // 1. 시작 단계에서 만든 실행 이력을 조회한다.
        SyncReconciliationRun run = getRun(result.runId());

        // 2. Batch 결과와 현재 완료 시각을 함께 기록해 RUNNING 이력을 완료 상태로 닫는다.
        run.complete(
            result.endCursor(),
            result.scannedCount(),
            result.detectedCount(),
            result.repairRequestedCount(),
            LocalDateTime.now(clock)
        );
    }

    /**
     * 검사 또는 완료 기록 중 발생한 오류 코드를 실행 이력의 최종 실패로 남긴다.
     */
    public void fail(UUID runId, String errorCode) {
        getRun(runId).fail(errorCode, LocalDateTime.now(clock));
    }

    /**
     * 실행 UUID로 Reconciliation 이력을 조회하고 없으면 내부 흐름 오류로 처리한다.
     */
    private SyncReconciliationRun getRun(UUID runId) {
        return syncReconciliationRunRepository.findByRunId(runId)
            .orElseThrow(() -> new IllegalStateException("Reconciliation 실행 이력을 찾을 수 없습니다."));
    }
}
