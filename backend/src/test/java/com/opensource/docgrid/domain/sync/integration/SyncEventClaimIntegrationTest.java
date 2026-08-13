package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncEventClaimService;

/**
 * 실제 PostgreSQL SKIP LOCKED Queue에서 다중 Dispatcher가 한 Event를 중복 Claim하지 않는지 검증한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sync Event Claim 통합 테스트")
class SyncEventClaimIntegrationTest {

    private static final int COMPETITOR_COUNT = 20;

    @Autowired private SyncOutboxEventRepository syncOutboxEventRepository;
    @Autowired private SyncEventClaimService syncEventClaimService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        // 이 Test Schema에 남은 Event가 경쟁 대상에 섞이지 않도록 기존 Queue를 종결 상태로 격리한다.
        jdbcTemplate.update("""
            UPDATE sync_outbox_events
               SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP,
                   claim_token = NULL, locked_by = NULL, lock_expires_at = NULL
             WHERE status IN ('PENDING', 'PROCESSING')
            """);
        LocalDateTime now = LocalDateTime.now().minusSeconds(1);
        SyncOutboxEvent event = syncOutboxEventRepository.saveAndFlush(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey("claim-integration:" + UUID.randomUUID())
                .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
                .aggregateId(9_999_999L)
                .aggregateVersion(1L)
                .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
                .payloadJson("{\"embeddingModelId\":1}")
                .availableAt(now)
                .occurredAt(now)
                .maxRetryCount(5)
                .build()
        );
        eventId = event.getEventId();
    }

    @AfterEach
    void tearDown() {
        syncOutboxEventRepository.findByEventId(eventId)
            .ifPresent(syncOutboxEventRepository::delete);
    }

    @Test
    @DisplayName("20개 Dispatcher가 동시에 경쟁해도 같은 Event Claim은 한 건이다")
    void claim_assignsSingleOwner_whenTwentyDispatchersCompete() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(COMPETITOR_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(COMPETITOR_COUNT);
        List<Future<Optional<ClaimedSyncEvent>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < COMPETITOR_COUNT; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return syncEventClaimService.claim();
                }));
            }

            List<ClaimedSyncEvent> claims = new ArrayList<>();
            for (Future<Optional<ClaimedSyncEvent>> future : futures) {
                future.get(15, TimeUnit.SECONDS).ifPresent(claims::add);
            }

            assertThat(claims).hasSize(1);
            assertThat(claims.get(0).eventId()).isEqualTo(eventId);
            assertThat(claims.get(0).claimToken()).isNotNull();
            SyncOutboxEvent persisted = syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
            assertThat(persisted.getClaimToken()).isEqualTo(claims.get(0).claimToken());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
