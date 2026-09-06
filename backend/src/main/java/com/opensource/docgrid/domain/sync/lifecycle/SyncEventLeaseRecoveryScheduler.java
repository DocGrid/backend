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

    /**
     * 만료 PROCESSING Event를 Batch 조회하고 Event별 독립 Transaction으로 회수한다.
     */
    @Scheduled(
        fixedDelayString = "${sync.dispatcher.lease-recovery-interval:10s}",
        initialDelayString = "${sync.dispatcher.lease-recovery-interval:10s}"
    )
    public void recoverExpiredLeases() {
        // 1. 이전 주기가 끝나지 않았으면 같은 인스턴스의 중복 복구를 건너뛴다.
        if (!recovering.compareAndSet(false, true)) {
            return;
        }
        try {
            // 2. 후보 조회와 후보별 재검증이 같은 Lease 경계를 사용하도록 기준 시각을 한 번만 구한다.
            LocalDateTime recoveredAt = LocalDateTime.now(clock);

            // 3. 한 주기의 부하가 제한되도록 설정된 Batch 크기만큼 후보 식별자만 조회한다.
            List<UUID> eventIds = syncOutboxEventRepository.findExpiredProcessingEventIds(
                recoveredAt,
                syncDispatcherProperties.getLeaseRecoveryBatchSize()
            );

            // 4. 각 후보를 독립 처리해 한 Event의 실패가 나머지 Lease 회수를 막지 않게 한다.
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
            // 5. 모든 성공·실패 경로에서 다음 복구 주기가 진입할 수 있도록 Guard를 해제한다.
            recovering.set(false);
        }
    }
}
