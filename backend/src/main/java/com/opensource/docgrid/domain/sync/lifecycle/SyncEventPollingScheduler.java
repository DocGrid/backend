package com.opensource.docgrid.domain.sync.lifecycle;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.service.command.SyncEventClaimService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventDispatchService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventFailureService;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PENDING Sync Event를 하나씩 Claim해 멱등 Handler로 전달하는 Polling Scheduler다.
 *
 * <p>한 인스턴스의 이전 주기가 끝나기 전에 다음 주기가 겹치지 않으며, Handler 실패는 Rollback 후 별도
 * 실패 Transaction으로 Retry 예약을 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sync.dispatcher", name = "enabled", havingValue = "true")
public class SyncEventPollingScheduler {

    private static final String HANDLER_FAILURE_MESSAGE = "Sync Event Handler 실행에 실패했습니다.";

    private final SyncEventClaimService syncEventClaimService;
    private final SyncEventDispatchService syncEventDispatchService;
    private final SyncEventFailureService syncEventFailureService;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @Scheduled(
        fixedDelayString = "${sync.dispatcher.polling-interval:1s}",
        initialDelayString = "${sync.dispatcher.polling-interval:1s}"
    )
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            Optional<ClaimedSyncEvent> claimedEvent = syncEventClaimService.claim();
            claimedEvent.ifPresent(this::dispatch);
        } catch (RuntimeException exception) {
            log.error("Sync Event Claim에 실패했습니다. errorCode={}", diagnosticCode(exception));
        } finally {
            polling.set(false);
        }
    }

    private void dispatch(ClaimedSyncEvent claimedEvent) {
        try {
            syncEventDispatchService.dispatch(claimedEvent);
        } catch (RuntimeException exception) {
            String errorCode = diagnosticCode(exception);
            log.error("Sync Event 처리에 실패했습니다. eventId={}, errorCode={}", claimedEvent.eventId(), errorCode);
            try {
                syncEventFailureService.recordFailure(
                    claimedEvent.eventId(),
                    claimedEvent.claimToken(),
                    errorCode,
                    HANDLER_FAILURE_MESSAGE
                );
            } catch (RuntimeException failureTransitionException) {
                // Lease가 먼저 만료됐다면 Recovery Scheduler가 회수하므로 현재 주기에서 소유권을 덮지 않는다.
                log.error(
                    "Sync Event 실패 상태 기록에 실패했습니다. eventId={}, errorCode={}",
                    claimedEvent.eventId(),
                    diagnosticCode(failureTransitionException)
                );
            }
        }
    }

    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
