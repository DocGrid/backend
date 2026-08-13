package com.opensource.docgrid.domain.sync.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.sync.enums.SyncEventDeliveryAttemptStatus;

/**
 * Sync Event 전달 Attempt가 성공·실패 원인을 한 번만 종결하는지 검증한다.
 */
@DisplayName("SyncEventDeliveryAttempt 단위 테스트")
class SyncEventDeliveryAttemptTest {

    @Test
    @DisplayName("실패 종결은 원인과 완료 시각을 보존하고 재종결을 거부한다")
    void fail_preservesCauseAndRejectsSecondCompletion() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 13, 19, 0);
        SyncEventDeliveryAttempt attempt = attempt(startedAt);

        attempt.fail("SYNC_LEASE_EXPIRED", "Lease 만료", startedAt.plusSeconds(30));

        assertThat(attempt.getStatus()).isEqualTo(SyncEventDeliveryAttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("SYNC_LEASE_EXPIRED");
        assertThat(attempt.getErrorMessage()).isEqualTo("Lease 만료");
        assertThat(attempt.getCompletedAt()).isEqualTo(startedAt.plusSeconds(30));
        assertThatThrownBy(() -> attempt.succeed(startedAt.plusSeconds(31)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("성공 종결은 오류 없이 완료 시각을 기록한다")
    void succeed_recordsCompletionWithoutError() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 13, 19, 0);
        SyncEventDeliveryAttempt attempt = attempt(startedAt);

        attempt.succeed(startedAt.plusSeconds(2));

        assertThat(attempt.getStatus()).isEqualTo(SyncEventDeliveryAttemptStatus.SUCCEEDED);
        assertThat(attempt.getCompletedAt()).isEqualTo(startedAt.plusSeconds(2));
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getErrorMessage()).isNull();
    }

    private SyncEventDeliveryAttempt attempt(LocalDateTime startedAt) {
        return SyncEventDeliveryAttempt.builder()
            .eventId(UUID.randomUUID())
            .claimToken(UUID.randomUUID())
            .attemptNo(1)
            .dispatcherName("sync-dispatcher-test")
            .startedAt(startedAt)
            .build();
    }
}
