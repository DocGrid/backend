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

    @Scheduled(
        fixedDelayString = "${sync.reconciliation.interval:5m}",
        initialDelayString = "${sync.reconciliation.interval:5m}"
    )
    public void reconcile() {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            long startCursor = cursor.get();
            SyncReconciliationBatchResult result = syncReconciliationOrchestrator.reconcileBatch(
                startCursor,
                properties.getMode()
            );
            cursor.set(result.hasMore() ? result.endCursor() : 0L);
        } catch (RuntimeException exception) {
            // 실패한 Cursor를 유지해 다음 주기에 같은 범위를 다시 검사한다.
            log.error(
                "Sync Reconciliation에 실패했습니다. cursor={}, errorType={}",
                cursor.get(),
                exception.getClass().getSimpleName()
            );
        } finally {
            reconciling.set(false);
        }
    }
}
