package com.opensource.docgrid.domain.sync.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.service.SyncReconciliationOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전체 DocumentVersion을 작은 ID Cursor Batch로 순회하고 마지막 Batch 뒤 처음부터 재검사한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sync.reconciliation", name = "enabled", havingValue = "true")
public class SyncReconciliationScheduler {

    private final SyncReconciliationOrchestrator syncReconciliationOrchestrator;
    private final SyncReconciliationProperties properties;
    private final AtomicBoolean reconciling = new AtomicBoolean(false);
    private final AtomicLong cursor = new AtomicLong(0L);

    /**
     * 현재 Cursor부터 한 Reconciliation Batch를 실행하고 다음 검사 범위를 기억한다.
     */
    @Scheduled(
        fixedDelayString = "${sync.reconciliation.interval:5m}",
        initialDelayString = "${sync.reconciliation.interval:5m}"
    )
    public void reconcile() {
        // 1. 이전 검사가 끝나지 않았으면 같은 인스턴스의 중복 Batch 실행을 건너뛴다.
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            // 2. 이번 Batch 시작 Cursor를 고정하고 설정된 관찰·복구 모드로 검사를 실행한다.
            long startCursor = cursor.get();
            SyncReconciliationBatchResult result = syncReconciliationOrchestrator.reconcileBatch(
                startCursor,
                properties.getMode()
            );

            // 3. 다음 데이터가 있으면 종료 Cursor로 이동하고 전체 순회가 끝나면 처음부터 다시 검사한다.
            cursor.set(result.hasMore() ? result.endCursor() : 0L);
        } catch (RuntimeException exception) {
            // 4. 실패한 Cursor를 유지해 다음 주기에 같은 범위를 다시 검사한다.
            log.error(
                "Sync Reconciliation에 실패했습니다. cursor={}, errorType={}",
                cursor.get(),
                exception.getClass().getSimpleName()
            );
        } finally {
            // 5. 성공·실패와 관계없이 다음 Scheduler 호출이 진입할 수 있도록 Guard를 해제한다.
            reconciling.set(false);
        }
    }
}
