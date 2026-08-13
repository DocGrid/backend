package com.opensource.docgrid.domain.sync.service.command;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventRetrySchedule;

import lombok.RequiredArgsConstructor;

/**
 * 만료 PROCESSING Event 후보를 독립 Transaction에서 다시 잠그고 Retry 또는 최종 실패로 회수한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventLeaseRecoveryService {

    private static final String LEASE_EXPIRED_CODE = "SYNC_LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE = "Sync Dispatcher Lease가 만료되어 실행을 회수했습니다.";

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventRetrySchedule syncEventRetrySchedule;
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryResult recover(UUID eventId, LocalDateTime recoveredAt) {
        Optional<SyncOutboxEvent> candidate = syncOutboxEventRepository
            .findExpiredByEventIdForUpdateSkipLocked(eventId, recoveredAt);
        if (candidate.isEmpty()) {
            return new RecoveryResult(eventId, false, null);
        }

        SyncOutboxEvent event = candidate.get();
        syncEventDeliveryAttemptService.fail(
            event.getEventId(),
            event.getClaimToken(),
            LEASE_EXPIRED_CODE,
            LEASE_EXPIRED_MESSAGE,
            recoveredAt
        );
        event.recoverExpiredLease(
            LEASE_EXPIRED_CODE,
            LEASE_EXPIRED_MESSAGE,
            recoveredAt,
            syncEventRetrySchedule.nextAvailableAt(event, recoveredAt)
        );
        return new RecoveryResult(eventId, true, event.getStatus());
    }

    /**
     * 만료 후보가 실제 상태 전이를 수행했는지와 회수 후 상태를 Scheduler에 전달한다.
     */
    public record RecoveryResult(UUID eventId, boolean recovered, SyncEventStatus status) {
    }
}
