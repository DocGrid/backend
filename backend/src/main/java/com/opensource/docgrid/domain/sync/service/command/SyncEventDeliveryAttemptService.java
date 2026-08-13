package com.opensource.docgrid.domain.sync.service.command;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncEventDeliveryAttempt;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncEventDeliveryAttemptRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sync Event Claim·성공·실패 이력을 호출한 Queue 상태 Transaction과 함께 기록한다.
 *
 * <p>이 Service는 별도 Transaction을 열지 않으며 Claim, Dispatch, Failure, Recovery Service의 기존
 * 경계에 참여해 Queue 상태와 이력이 서로 다른 결과로 Commit되지 않도록 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncEventDeliveryAttemptService {

    private final SyncEventDeliveryAttemptRepository repository;

    public void start(SyncOutboxEvent event, UUID claimToken, LocalDateTime startedAt) {
        if (event == null
            || event.getStatus() != SyncEventStatus.PROCESSING
            || event.getEventId() == null
            || !Objects.equals(event.getClaimToken(), claimToken)
            || event.getLockedBy() == null
            || event.getLockedBy().isBlank()
            || startedAt == null) {
            throw new IllegalArgumentException("현재 Event Claim과 일치하는 실행 정보가 필요합니다.");
        }
        repository.save(SyncEventDeliveryAttempt.builder()
            .eventId(event.getEventId())
            .claimToken(claimToken)
            .attemptNo(event.getRetryCount() + 1)
            .dispatcherName(event.getLockedBy())
            .startedAt(startedAt)
            .build());
    }

    public void succeed(UUID eventId, UUID claimToken, LocalDateTime succeededAt) {
        repository.findByEventIdAndClaimTokenForUpdate(eventId, claimToken)
            .ifPresent(attempt -> attempt.succeed(succeededAt));
    }

    public void fail(
        UUID eventId,
        UUID claimToken,
        String errorCode,
        String errorMessage,
        LocalDateTime failedAt
    ) {
        repository.findByEventIdAndClaimTokenForUpdate(eventId, claimToken)
            .ifPresent(attempt -> attempt.fail(errorCode, errorMessage, failedAt));
    }
}
