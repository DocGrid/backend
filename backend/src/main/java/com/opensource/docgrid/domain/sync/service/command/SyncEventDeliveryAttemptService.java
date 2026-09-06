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

    /**
     * 새 Event Claim 세대의 Delivery Attempt를 현재 Retry 순번으로 시작한다.
     */
    public void start(SyncOutboxEvent event, UUID claimToken, LocalDateTime startedAt) {
        // 1. Queue Entity의 현재 PROCESSING 소유권과 전달받은 Claim 정보가 일치하는지 확인한다.
        if (event == null
            || event.getStatus() != SyncEventStatus.PROCESSING
            || event.getEventId() == null
            || !Objects.equals(event.getClaimToken(), claimToken)
            || event.getLockedBy() == null
            || event.getLockedBy().isBlank()
            || startedAt == null) {
            throw new IllegalArgumentException("현재 Event Claim과 일치하는 실행 정보가 필요합니다.");
        }

        // 2. 현재 Retry 횟수의 다음 순번과 Dispatcher 이름을 불변 실행 이력으로 저장한다.
        repository.save(SyncEventDeliveryAttempt.builder()
            .eventId(event.getEventId())
            .claimToken(claimToken)
            .attemptNo(event.getRetryCount() + 1)
            .dispatcherName(event.getLockedBy())
            .startedAt(startedAt)
            .build());
    }

    /**
     * Event와 Claim Token이 일치하는 Delivery Attempt가 있으면 성공 시각으로 종결한다.
     */
    public void succeed(UUID eventId, UUID claimToken, LocalDateTime succeededAt) {
        // Claim별 유일 Attempt를 잠근 뒤 존재하는 경우에만 멱등하게 성공 상태를 반영한다.
        repository.findByEventIdAndClaimTokenForUpdate(eventId, claimToken)
            .ifPresent(attempt -> attempt.succeed(succeededAt));
    }

    /**
     * Event와 Claim Token이 일치하는 Delivery Attempt가 있으면 제한된 실패 정보로 종결한다.
     */
    public void fail(
        UUID eventId,
        UUID claimToken,
        String errorCode,
        String errorMessage,
        LocalDateTime failedAt
    ) {
        // Claim별 유일 Attempt를 잠가 성공 처리와 충돌하지 않는 동일 Transaction 안에서 실패시킨다.
        repository.findByEventIdAndClaimTokenForUpdate(eventId, claimToken)
            .ifPresent(attempt -> attempt.fail(errorCode, errorMessage, failedAt));
    }
}
