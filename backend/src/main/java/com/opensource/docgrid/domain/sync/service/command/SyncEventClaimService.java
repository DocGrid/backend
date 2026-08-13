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
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    private final Clock clock;

    public Optional<ClaimedSyncEvent> claim() {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        return syncOutboxEventRepository.findNextPendingForUpdate(claimedAt)
            .map(event -> claim(event, claimedAt));
    }

    private ClaimedSyncEvent claim(SyncOutboxEvent event, LocalDateTime claimedAt) {
        // 1. 이전 실행과 구분되는 Claim 세대 Token을 발급한다.
        UUID claimToken = UUID.randomUUID();
        // 2. Queue 상태와 현재 Dispatcher Lease 소유권을 함께 설정한다.
        event.claim(
            syncDispatcherProperties.getName(),
            claimToken,
            claimedAt,
            claimedAt.plus(syncDispatcherProperties.getLeaseDuration())
        );
        // 3. Queue 소유권과 같은 Transaction에 Claim 세대 실행 이력을 시작한다.
        syncEventDeliveryAttemptService.start(event, claimToken, claimedAt);
        return new ClaimedSyncEvent(event.getEventId(), claimToken);
    }
}
