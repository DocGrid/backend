package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventRetrySchedule;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Rollback된 Handler 실행의 실패를 별도 Transaction에서 Retry 또는 최종 실패 상태로 기록한다.
 *
 * <p>호출자는 민감한 예외 Message 대신 안정적인 오류 코드와 제한된 진단 문구만 전달해야 한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventFailureService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventRetrySchedule syncEventRetrySchedule;
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID eventId, UUID claimToken, String errorCode, String errorMessage) {
        LocalDateTime failedAt = LocalDateTime.now(clock);
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        validateOwnership(event, claimToken, failedAt);
        syncEventDeliveryAttemptService.fail(eventId, claimToken, errorCode, errorMessage, failedAt);

        // 이번 실패가 허용 횟수를 채우면 다시 Claim되지 않는 최종 상태로 종결한다.
        if (event.getRetryCount() + 1 >= event.getMaxRetryCount()) {
            event.markFailed(claimToken, errorCode, errorMessage, failedAt);
            return;
        }
        event.scheduleRetry(
            claimToken,
            errorCode,
            errorMessage,
            failedAt,
            syncEventRetrySchedule.nextAvailableAt(event, failedAt)
        );
    }

    private void validateOwnership(
        SyncOutboxEvent event,
        UUID claimToken,
        LocalDateTime failedAt
    ) {
        if (event.getStatus() != SyncEventStatus.PROCESSING
            || !Objects.equals(event.getClaimToken(), claimToken)
            || event.getLockExpiresAt() == null
            || !event.getLockExpiresAt().isAfter(failedAt)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID);
        }
    }
}
