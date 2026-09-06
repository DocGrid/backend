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

    /**
     * 현재 실행 가능한 우선 Event 하나를 Claim한다.
     *
     * @return Claim 세대 정보, 처리 가능한 PENDING Event가 없으면 빈 값
     */
    public Optional<ClaimedSyncEvent> claim() {
        // 1. Event 선택과 Lease 계산이 같은 시각을 사용하도록 기준 시각을 한 번만 구한다.
        LocalDateTime claimedAt = LocalDateTime.now(clock);

        // 2. 잠기지 않은 다음 Event가 있을 때만 PROCESSING 전이와 응답 생성을 수행한다.
        return syncOutboxEventRepository.findNextPendingForUpdate(claimedAt)
            .map(event -> claim(event, claimedAt));
    }

    /**
     * 잠긴 Event에 새 Claim 세대를 부여하고 Delivery Attempt를 시작한다.
     */
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
