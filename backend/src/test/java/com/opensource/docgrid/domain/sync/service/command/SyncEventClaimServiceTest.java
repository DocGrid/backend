package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.sync.config.SyncDispatcherProperties;
import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

/**
 * SyncEventClaimService가 Queue 조회와 Lease 발급을 하나의 상태 전이로 수행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventClaimService 단위 테스트")
class SyncEventClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T09:30:00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock private SyncOutboxEventRepository syncOutboxEventRepository;
    @Mock private SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;

    private SyncEventClaimService service;
    private SyncDispatcherProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SyncDispatcherProperties();
        properties.setName("dispatcher-test");
        properties.setLeaseDuration(Duration.ofSeconds(30));
        service = new SyncEventClaimService(
            syncOutboxEventRepository,
            properties,
            syncEventDeliveryAttemptService,
            Clock.fixed(NOW, ZONE_ID)
        );
    }

    @Test
    @DisplayName("가장 오래된 PENDING Event에 고유 Claim Token과 Lease를 부여한다")
    void claim_assignsOwnershipAndLease() {
        LocalDateTime claimedAt = LocalDateTime.ofInstant(NOW, ZONE_ID);
        SyncOutboxEvent event = event(claimedAt);
        given(syncOutboxEventRepository.findNextPendingForUpdate(claimedAt)).willReturn(Optional.of(event));

        Optional<ClaimedSyncEvent> result = service.claim();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().eventId()).isEqualTo(event.getEventId());
        assertThat(result.orElseThrow().claimToken()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        assertThat(event.getLockedBy()).isEqualTo("dispatcher-test");
        assertThat(event.getLockExpiresAt()).isEqualTo(claimedAt.plusSeconds(30));
        then(syncEventDeliveryAttemptService).should().start(
            event,
            result.orElseThrow().claimToken(),
            claimedAt
        );
    }

    private SyncOutboxEvent event(LocalDateTime availableAt) {
        return SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("claim:" + UUID.randomUUID())
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(1L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{\"embeddingModelId\":1}")
            .availableAt(availableAt)
            .occurredAt(availableAt)
            .maxRetryCount(3)
            .build();
    }
}
