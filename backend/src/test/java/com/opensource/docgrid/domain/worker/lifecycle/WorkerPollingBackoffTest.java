package com.opensource.docgrid.domain.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 빈 Queue Polling 지연의 지수 증가, 상한과 Claim 성공 초기화를 검증한다.
 */
@DisplayName("WorkerPollingBackoff 테스트")
class WorkerPollingBackoffTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    @DisplayName("빈 Queue가 이어지면 2초, 4초, 8초, 최대 10초까지 Polling을 늦춘다")
    void emptyQueue_increasesDelay_upToMaximum() {
        WorkerPollingBackoff backoff = new WorkerPollingBackoff(
            Duration.ofSeconds(1),
            Duration.ofSeconds(10)
        );

        assertThat(backoff.isPollingDue(STARTED_AT)).isTrue();

        assertNextDelay(backoff, STARTED_AT, Duration.ofSeconds(2));
        assertNextDelay(backoff, STARTED_AT.plusSeconds(2), Duration.ofSeconds(4));
        assertNextDelay(backoff, STARTED_AT.plusSeconds(6), Duration.ofSeconds(8));
        assertNextDelay(backoff, STARTED_AT.plusSeconds(14), Duration.ofSeconds(10));
        assertNextDelay(backoff, STARTED_AT.plusSeconds(24), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Job을 Claim하면 Backoff를 초기화해 즉시 Polling할 수 있다")
    void reset_allowsImmediatePolling() {
        WorkerPollingBackoff backoff = new WorkerPollingBackoff(
            Duration.ofSeconds(1),
            Duration.ofSeconds(10)
        );
        backoff.recordEmptyQueue(STARTED_AT);

        backoff.reset();

        assertThat(backoff.isPollingDue(STARTED_AT)).isTrue();
    }

    private void assertNextDelay(
        WorkerPollingBackoff backoff,
        Instant emptyQueueCheckedAt,
        Duration expectedDelay
    ) {
        backoff.recordEmptyQueue(emptyQueueCheckedAt);

        assertThat(backoff.isPollingDue(emptyQueueCheckedAt.plus(expectedDelay).minusNanos(1))).isFalse();
        assertThat(backoff.isPollingDue(emptyQueueCheckedAt.plus(expectedDelay))).isTrue();
    }
}
