package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventHandlerRegistry;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 현재 Claim을 재검증한 뒤 Handler 부작용과 Event 완료를 하나의 독립 Transaction에서 확정한다.
 *
 * <p>Handler가 실패하면 전체 Transaction이 Rollback되므로 Event는 PROCESSING에 남고, 호출 Scheduler가
 * 별도 실패 전이 Service로 Retry를 예약한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventDispatchService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventHandlerRegistry syncEventHandlerRegistry;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(ClaimedSyncEvent claimedEvent) {
        LocalDateTime dispatchedAt = LocalDateTime.now(clock);
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(claimedEvent.eventId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        validateOwnership(event, claimedEvent, dispatchedAt);

        // Handler 부작용과 완료 상태가 같은 Commit 경계를 공유해야 부분 완료가 남지 않는다.
        syncEventHandlerRegistry.handle(event);
        event.complete(claimedEvent.claimToken(), LocalDateTime.now(clock));
    }

    private void validateOwnership(
        SyncOutboxEvent event,
        ClaimedSyncEvent claimedEvent,
        LocalDateTime dispatchedAt
    ) {
        if (event.getStatus() != SyncEventStatus.PROCESSING
            || !Objects.equals(event.getClaimToken(), claimedEvent.claimToken())
            || event.getLockExpiresAt() == null
            || !event.getLockExpiresAt().isAfter(dispatchedAt)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID);
        }
    }
}
