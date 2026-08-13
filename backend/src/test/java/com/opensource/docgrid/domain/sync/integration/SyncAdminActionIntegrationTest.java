package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.sync.dto.response.SyncAdminActionResponse;
import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAdminActionType;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncAdminActionRepository;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncAdminCommandService;

/**
 * 실제 PostgreSQL에서 실패 Event 재시도와 관리자 감사 Action이 함께 Commit되는지 검증한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sync 관리자 감사 Action 통합 테스트")
class SyncAdminActionIntegrationTest {

    @Autowired private SyncAdminCommandService syncAdminCommandService;
    @Autowired private SyncOutboxEventRepository syncOutboxEventRepository;
    @Autowired private SyncAdminActionRepository syncAdminActionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID eventId;
    private UUID actionId;

    @AfterEach
    void tearDown() {
        if (actionId != null) {
            syncAdminActionRepository.findByActionId(actionId)
                .ifPresent(syncAdminActionRepository::delete);
        }
        if (eventId != null) {
            syncOutboxEventRepository.findByEventId(eventId)
                .ifPresent(syncOutboxEventRepository::delete);
        }
    }

    @Test
    @DisplayName("FAILED Event 재시도는 PENDING 전이와 관리자·대상 Event 감사 이력을 함께 남긴다")
    void retryEvent_persistsAuditedStateTransition() {
        Long adminUserId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = 'kcw130502@gmail.com'",
            Long.class
        );
        SyncOutboxEvent failedEvent = failedEvent();
        eventId = failedEvent.getEventId();

        SyncAdminActionResponse response = syncAdminCommandService.retryEvent(eventId, adminUserId);
        actionId = response.actionId();

        SyncOutboxEvent retriedEvent = syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
        SyncAdminAction action = syncAdminActionRepository.findByActionId(actionId).orElseThrow();
        assertThat(retriedEvent.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertThat(action.getActionType()).isEqualTo(SyncAdminActionType.EVENT_RETRIED);
        assertThat(action.getTargetId()).isEqualTo(eventId.toString());
        assertThat(action.getAdminUser().getId()).isEqualTo(adminUserId);
    }

    private SyncOutboxEvent failedEvent() {
        LocalDateTime now = LocalDateTime.now();
        SyncOutboxEvent event = syncOutboxEventRepository.saveAndFlush(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey("admin-action-integration:" + UUID.randomUUID())
                .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
                .aggregateId(9_999_999L)
                .aggregateVersion(1L)
                .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
                .payloadJson("{\"embeddingModelId\":1}")
                .availableAt(now.minusMinutes(2))
                .occurredAt(now.minusMinutes(2))
                .maxRetryCount(1)
                .build()
        );
        UUID claimToken = UUID.randomUUID();
        event.claim("admin-action-test", claimToken, now.minusMinutes(1), now.plusMinutes(1));
        event.markFailed(claimToken, "TEST_FAILURE", "감사 테스트 실패", now);
        return syncOutboxEventRepository.saveAndFlush(event);
    }
}
