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

    /**
     * 다음 실행 가능한 Sync Event 하나를 Claim하고 존재하면 Handler 처리로 넘긴다.
     */
    @Scheduled(
        fixedDelayString = "${sync.dispatcher.polling-interval:1s}",
        initialDelayString = "${sync.dispatcher.polling-interval:1s}"
    )
    public void poll() {
        // 1. fixedDelay 호출이 비정상적으로 겹쳐도 한 애플리케이션 인스턴스에서는 한 주기만 실행한다.
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            // 2. 짧은 Claim Transaction에서 Event 소유권을 얻고 실제 처리는 별도 Transaction으로 분리한다.
            Optional<ClaimedSyncEvent> claimedEvent = syncEventClaimService.claim();
            claimedEvent.ifPresent(this::dispatch);
        } catch (RuntimeException exception) {
            // 3. Claim 실패가 Scheduler Thread를 종료하지 않도록 진단 코드만 기록한다.
            log.error("Sync Event Claim에 실패했습니다. errorCode={}", diagnosticCode(exception));
        } finally {
            // 4. 성공·실패와 관계없이 다음 Polling 주기가 실행될 수 있도록 로컬 Guard를 해제한다.
            polling.set(false);
        }
    }

    /**
     * Claim한 Event를 멱등 Handler로 전달하고 실패하면 현재 Claim Token으로 Retry 상태를 기록한다.
     */
    private void dispatch(ClaimedSyncEvent claimedEvent) {
        try {
            // 1. Handler 실행과 성공 전이는 Dispatch Service의 Transaction 안에서 함께 처리한다.
            syncEventDispatchService.dispatch(claimedEvent);
        } catch (RuntimeException exception) {
            // 2. Handler 실패는 원본 메시지 대신 안정적인 오류 코드로 기록한다.
            String errorCode = diagnosticCode(exception);
            log.error("Sync Event 처리에 실패했습니다. eventId={}, errorCode={}", claimedEvent.eventId(), errorCode);
            try {
                // 3. Rollback된 처리와 분리된 Transaction에서 Retry 횟수와 다음 실행 시각을 저장한다.
                syncEventFailureService.recordFailure(
                    claimedEvent.eventId(),
                    claimedEvent.claimToken(),
                    errorCode,
                    HANDLER_FAILURE_MESSAGE
                );
            } catch (RuntimeException failureTransitionException) {
                // 4. Lease가 먼저 만료됐다면 Recovery Scheduler가 회수하므로 현재 주기에서 소유권을 덮지 않는다.
                log.error(
                    "Sync Event 실패 상태 기록에 실패했습니다. eventId={}, errorCode={}",
                    claimedEvent.eventId(),
                    diagnosticCode(failureTransitionException)
                );
            }
        }
    }

    /**
     * 운영 로그와 실패 이력에 예외 메시지를 노출하지 않고 오류 코드 또는 예외 유형만 반환한다.
     */
    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
