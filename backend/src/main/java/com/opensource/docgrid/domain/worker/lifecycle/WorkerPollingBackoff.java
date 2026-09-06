package com.opensource.docgrid.domain.worker.lifecycle;

import java.time.Duration;
import java.time.Instant;

/**
 * 빈 인덱싱 Queue를 반복 조회하지 않도록 다음 Polling 허용 시각을 관리한다.
 *
 * <p>Scheduler 실행 주기 자체는 유지하고 DB Claim 시도만 지연한다. Job Claim 성공 시 상태를 초기화하며,
 * Claim 오류는 Queue가 비었다는 신호가 아니므로 Backoff 단계에 반영하지 않는다.
 */
final class WorkerPollingBackoff {

    private static final Instant IMMEDIATE = Instant.MIN;

    private final Duration pollingInterval;
    private final Duration maxPollingInterval;
    private Duration currentDelay;
    private Instant nextPollingAt = IMMEDIATE;

    /**
     * 기본 Polling 간격과 빈 Queue에서 허용할 최대 간격으로 Backoff 상태를 초기화한다.
     */
    WorkerPollingBackoff(Duration pollingInterval, Duration maxPollingInterval) {
        this.pollingInterval = pollingInterval;
        this.maxPollingInterval = maxPollingInterval;
    }

    /**
     * 현재 시각이 다음 DB Polling 허용 시각에 도달했는지 반환한다.
     */
    boolean isPollingDue(Instant now) {
        return !now.isBefore(nextPollingAt);
    }

    /**
     * 빈 Queue가 확인되면 지연을 두 배로 늘리되 설정된 상한을 넘지 않는다.
     */
    void recordEmptyQueue(Instant now) {
        // 1. 첫 빈 Queue에는 기본 Polling 간격을 기준으로 다음 Backoff 단계를 계산한다.
        Duration previousDelay = currentDelay == null ? pollingInterval : currentDelay;

        // 2. 상한 안에서 지연을 늘리고 현재 시각으로부터 다음 DB 접근 가능 시각을 고정한다.
        currentDelay = doubledWithinLimit(previousDelay);
        nextPollingAt = now.plus(currentDelay);
    }

    /**
     * Job을 Claim하면 다음 기본 Scheduler 실행에서 즉시 Queue를 다시 확인할 수 있도록 초기화한다.
     */
    void reset() {
        currentDelay = null;
        nextPollingAt = IMMEDIATE;
    }

    /**
     * 지연을 두 배로 계산하되 곱셈 Overflow 없이 최대 Polling 간격에서 고정한다.
     */
    private Duration doubledWithinLimit(Duration delay) {
        if (delay.compareTo(maxPollingInterval.dividedBy(2)) >= 0) {
            return maxPollingInterval;
        }
        return delay.multipliedBy(2);
    }
}
