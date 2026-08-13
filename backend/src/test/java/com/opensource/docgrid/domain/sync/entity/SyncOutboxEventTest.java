package com.opensource.docgrid.domain.sync.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;

/**
 * Sync Outbox Event의 Claim, 완료, Retry와 만료 Lease 복구 상태 불변식을 검증한다.
 */
@DisplayName("SyncOutboxEvent 상태 전이 테스트")
class SyncOutboxEventTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 18, 30);

    @Test
    @DisplayName("PENDING Event를 Claim하고 같은 Token으로 완료한다")
    void claimAndComplete_transitionsToProcessed() {
        SyncOutboxEvent event = event(3);
        UUID claimToken = UUID.randomUUID();

        event.claim("dispatcher-1", claimToken, NOW, NOW.plusSeconds(30));
        event.complete(claimToken, NOW.plusSeconds(1));

        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.getClaimToken()).isNull();
        assertThat(event.getLockedBy()).isNull();
        assertThat(event.getLockExpiresAt()).isNull();
    }

    @Test
    @DisplayName("다른 Claim Token으로 완료할 수 없다")
    void completeRejectsStaleClaimToken() {
        SyncOutboxEvent event = event(3);
        event.claim("dispatcher-1", UUID.randomUUID(), NOW, NOW.plusSeconds(30));

        assertThatThrownBy(() -> event.complete(UUID.randomUUID(), NOW.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
    }

    @Test
    @DisplayName("만료 Lease는 Retry Queue로 회수하고 횟수를 소진하면 FAILED로 종결한다")
    void recoverExpiredLease_retriesThenFails() {
        SyncOutboxEvent event = event(2);
        event.claim("dispatcher-1", UUID.randomUUID(), NOW, NOW.plusSeconds(10));

        event.recoverExpiredLease("EXPIRED", "expired", NOW.plusSeconds(10), NOW.plusSeconds(15));
        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertThat(event.getRetryCount()).isOne();

        event.claim("dispatcher-2", UUID.randomUUID(), NOW.plusSeconds(15), NOW.plusSeconds(25));
        event.recoverExpiredLease("EXPIRED", "expired", NOW.plusSeconds(25), NOW.plusSeconds(30));

        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getClaimToken()).isNull();
    }

    private SyncOutboxEvent event(int maxRetryCount) {
        return SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("test:" + UUID.randomUUID())
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(1L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{\"embeddingModelId\":1}")
            .availableAt(NOW)
            .occurredAt(NOW)
            .maxRetryCount(maxRetryCount)
            .build();
    }
}
