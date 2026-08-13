package com.opensource.docgrid.domain.sync.lifecycle;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.config.SyncDispatcherProperties;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncEventLeaseRecoveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 만료된 PROCESSING Sync Event를 제한 Batch로 찾아 독립 복구 Transaction에 전달한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sync.dispatcher", name = "enabled", havingValue = "true")
public class SyncEventLeaseRecoveryScheduler {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventLeaseRecoveryService syncEventLeaseRecoveryService;
    private final SyncDispatcherProperties syncDispatcherProperties;
    private final Clock clock;
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    @Scheduled(
        fixedDelayString = "${sync.dispatcher.lease-recovery-interval:10s}",
        initialDelayString = "${sync.dispatcher.lease-recovery-interval:10s}"
    )
    public void recoverExpiredLeases() {
        if (!recovering.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime recoveredAt = LocalDateTime.now(clock);
            List<UUID> eventIds = syncOutboxEventRepository.findExpiredProcessingEventIds(
                recoveredAt,
                syncDispatcherProperties.getLeaseRecoveryBatchSize()
            );
            for (UUID eventId : eventIds) {
                try {
                    syncEventLeaseRecoveryService.recover(eventId, recoveredAt);
                } catch (RuntimeException exception) {
                    log.error(
                        "만료 Sync Event Lease 복구에 실패했습니다. eventId={}, errorType={}",
                        eventId,
                        exception.getClass().getSimpleName()
                    );
                }
            }
        } finally {
            recovering.set(false);
        }
    }
}
