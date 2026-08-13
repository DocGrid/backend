package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.config.SyncDispatcherProperties;
import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * 실행 가능한 PENDING Outbox Event 하나를 잠그고 현재 Dispatcher에 Lease 소유권을 부여한다.
 *
 * <p>행 선택과 PROCESSING 전이를 한 Transaction에서 수행해 다중 Scheduler가 같은 Event를 Claim하지
 * 못하게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncEventClaimService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncDispatcherProperties syncDispatcherProperties;
    private final Clock clock;

    public Optional<ClaimedSyncEvent> claim() {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        return syncOutboxEventRepository.findNextPendingForUpdate(claimedAt)
            .map(event -> claim(event, claimedAt));
    }

    private ClaimedSyncEvent claim(SyncOutboxEvent event, LocalDateTime claimedAt) {
        UUID claimToken = UUID.randomUUID();
        event.claim(
            syncDispatcherProperties.getName(),
            claimToken,
            claimedAt,
            claimedAt.plus(syncDispatcherProperties.getLeaseDuration())
        );
        return new ClaimedSyncEvent(event.getEventId(), claimToken);
    }
}
