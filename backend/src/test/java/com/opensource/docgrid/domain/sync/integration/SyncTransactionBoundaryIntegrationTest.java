package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncEventDeliveryAttempt;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncEventDeliveryAttemptRepository;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncEventDispatchService;
import com.opensource.docgrid.domain.user.repository.UserRepository;

/**
 * 실제 PostgreSQL 장애와 영속성 Context 초기화를 주입해 Sync Commit 경계를 검증한다.
 *
 * <p>Repository mock이 아닌 Trigger와 실제 Bulk Update를 사용한 뒤 남은 DB 행을 직접
 * 확인한다. Trigger는 고유한 테스트 표식에만 반응하고 각 테스트 종료 시 제거한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sync Outbox 트랜잭션 경계 통합 테스트")
class SyncTransactionBoundaryIntegrationTest {

    private static final String VERSION_TRIGGER = "docgrid_test_fail_document_version";
    private static final String VERSION_FUNCTION = "docgrid_test_raise_document_version_failure";
    private static final String EVENT_INSERT_TRIGGER = "docgrid_test_fail_outbox_insert";
    private static final String EVENT_INSERT_FUNCTION = "docgrid_test_raise_outbox_insert_failure";
    private static final String EVENT_COMPLETE_TRIGGER = "docgrid_test_fail_outbox_complete";
    private static final String EVENT_COMPLETE_FUNCTION = "docgrid_test_raise_outbox_complete_failure";

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private SyncOutboxEventRepository syncOutboxEventRepository;
    @Autowired private SyncEventDeliveryAttemptRepository syncEventDeliveryAttemptRepository;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private SyncEventDispatchService syncEventDispatchService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @MockitoBean
    private FileStorageService fileStorageService;

    private final List<DocumentUploadResponse> createdDocuments = new ArrayList<>();
    private final List<UUID> standaloneEventIds = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void setUp() {
        reset(fileStorageService);
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                "test-bucket",
                "documents/transaction-boundary/" + UUID.randomUUID()
            ));
    }

    @AfterEach
    void tearDown() {
        dropFailureTriggers();
        standaloneEventIds.forEach(eventId -> syncOutboxEventRepository.findByEventId(eventId)
            .ifPresent(syncOutboxEventRepository::delete));
        createdDocuments.forEach(this::deleteDocument);
    }

    @Test
    @DisplayName("Version 저장 실패 시 Document·Job·Outbox Event가 하나도 남지 않는다")
    void upload_rollsBackAllRows_whenVersionSaveFails() {
        String marker = "fail-version-" + UUID.randomUUID();
        long eventCountBefore = syncOutboxEventRepository.count();
        long jobCountBefore = embeddingJobRepository.count();
        installVersionInsertFailure(marker + ".txt");

        assertThatThrownBy(() -> documentUploadFacade.upload(
            userId,
            uploadRequest(marker, marker + ".txt")
        )).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT COUNT(*) FROM documents WHERE title = ?", marker)).isZero();
        assertThat(count(
            "SELECT COUNT(*) FROM document_versions WHERE original_filename = ?",
            marker + ".txt"
        )).isZero();
        assertThat(syncOutboxEventRepository.count()).isEqualTo(eventCountBefore);
        assertThat(embeddingJobRepository.count()).isEqualTo(jobCountBefore);
    }

    @Test
    @DisplayName("Outbox Event 저장 실패 시 앞서 저장한 Version과 후속 Job까지 함께 롤백한다")
    void upload_rollsBackVersionAndJob_whenEventSaveFails() {
        String marker = "fail-event-" + UUID.randomUUID();
        long eventCountBefore = syncOutboxEventRepository.count();
        long jobCountBefore = embeddingJobRepository.count();
        installEventInsertFailure(marker);

        assertThatThrownBy(() -> documentUploadFacade.upload(
            userId,
            uploadRequest(marker, marker + ".txt")
        )).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT COUNT(*) FROM documents WHERE title = ?", marker)).isZero();
        assertThat(count(
            "SELECT COUNT(*) FROM document_versions WHERE title_snapshot = ?",
            marker
        )).isZero();
        assertThat(syncOutboxEventRepository.count()).isEqualTo(eventCountBefore);
        assertThat(embeddingJobRepository.count()).isEqualTo(jobCountBefore);
    }

    @Test
    @DisplayName("Handler 작업 실패 시 Event는 PROCESSED로 전환되지 않는다")
    void dispatch_doesNotCompleteEvent_whenHandlerFails() {
        SyncOutboxEvent event = pendingEvent(9_999_999_999L);
        standaloneEventIds.add(event.getEventId());
        ClaimedSyncEvent claim = claim(event);

        assertThatThrownBy(() -> syncEventDispatchService.dispatch(claim))
            .isInstanceOf(RuntimeException.class);

        SyncOutboxEvent persisted = syncOutboxEventRepository.findByEventId(event.getEventId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        assertThat(persisted.getProcessedAt()).isNull();
    }

    @Test
    @DisplayName("Event 완료 저장이 실패하면 Handler가 만든 Job도 같은 Transaction에서 롤백한다")
    void dispatch_rollsBackHandlerSideEffect_whenCompletionFails() {
        DocumentUploadResponse response = uploadDocument("fail-complete-" + UUID.randomUUID());
        createdDocuments.add(response);
        UUID eventId = sourceEventId(response.embeddingJobId());
        embeddingJobRepository.deleteById(response.embeddingJobId());
        embeddingJobRepository.flush();
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
        ClaimedSyncEvent claim = claim(event);
        installEventCompletionFailure(eventId);

        assertThatThrownBy(() -> syncEventDispatchService.dispatch(claim))
            .isInstanceOf(RuntimeException.class);

        SyncOutboxEvent persisted = syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        assertThat(persisted.getProcessedAt()).isNull();
        assertThat(embeddingJobRepository.findBySourceEventId(eventId)).isEmpty();
    }

    @Test
    @DisplayName("권한 캐시 Bulk Update가 Context를 초기화해도 Event와 Attempt를 함께 완료한다")
    void dispatch_completesEventAndAttempt_whenPermissionCacheBulkUpdateClearsContext() {
        SyncOutboxEvent event = permissionCacheRevocationEvent();
        standaloneEventIds.add(event.getEventId());
        ClaimedSyncEvent claim = claim(event);

        syncEventDispatchService.dispatch(claim);

        SyncOutboxEvent persisted = syncOutboxEventRepository.findByEventId(event.getEventId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(persisted.getProcessedAt()).isNotNull();
        assertThat(persisted.getClaimToken()).isNull();
        assertThat(persisted.getLockedBy()).isNull();
        assertThat(persisted.getLockExpiresAt()).isNull();
        assertThat(count("""
            SELECT COUNT(*)
            FROM sync_event_delivery_attempts
            WHERE event_id = ? AND status = 'SUCCEEDED' AND completed_at IS NOT NULL
            """, event.getEventId())).isOne();
    }

    private SyncOutboxEvent pendingEvent(Long aggregateId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return syncOutboxEventRepository.saveAndFlush(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey("transaction-boundary:" + UUID.randomUUID())
                .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
                .aggregateId(aggregateId)
                .aggregateVersion(1L)
                .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
                .payloadJson("{\"embeddingModelId\":1}")
                .occurredAt(now)
                .availableAt(now)
                .maxRetryCount(3)
                .build()
        );
    }

    private SyncOutboxEvent permissionCacheRevocationEvent() {
        LocalDateTime now = LocalDateTime.now(clock);
        return syncOutboxEventRepository.saveAndFlush(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey("permission-cache-clear:" + UUID.randomUUID())
                .aggregateType(SyncAggregateType.PERMISSION)
                .aggregateId(9_999_999_998L)
                .eventType(SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED)
                .payloadJson("{\"sourceType\":\"DIRECT_DOCUMENT_PERMISSION\",\"operation\":\"REVOKED\"}")
                .occurredAt(now)
                .availableAt(now)
                .maxRetryCount(3)
                .build()
        );
    }

    private ClaimedSyncEvent claim(SyncOutboxEvent event) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        UUID claimToken = UUID.randomUUID();
        event.claim("transaction-boundary-test", claimToken, claimedAt, claimedAt.plusMinutes(5));
        syncOutboxEventRepository.saveAndFlush(event);
        syncEventDeliveryAttemptRepository.saveAndFlush(
            SyncEventDeliveryAttempt.builder()
                .eventId(event.getEventId())
                .claimToken(claimToken)
                .attemptNo(event.getRetryCount() + 1)
                .dispatcherName(event.getLockedBy())
                .startedAt(claimedAt)
                .build()
        );
        return new ClaimedSyncEvent(event.getEventId(), claimToken);
    }

    private DocumentUploadResponse uploadDocument(String marker) {
        return documentUploadFacade.upload(userId, uploadRequest(marker, marker + ".txt"));
    }

    private DocumentUploadRequest uploadRequest(String marker, String filename) {
        return new DocumentUploadRequest(
            new MockMultipartFile("file", filename, "text/plain", marker.getBytes()),
            marker,
            "트랜잭션 경계 통합 테스트",
            VisibilityType.PRIVATE
        );
    }

    private UUID sourceEventId(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            jobId
        );
    }

    private int count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    private void installVersionInsertFailure(String filename) {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_document_version_failure()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.original_filename = '%s' THEN
                    RAISE EXCEPTION 'forced document version failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(filename));
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_document_version
            BEFORE INSERT ON document_versions
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_document_version_failure()
            """);
    }

    private void installEventInsertFailure(String titleSnapshot) {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_outbox_insert_failure()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.aggregate_type = 'DOCUMENT_VERSION'
                   AND EXISTS (
                       SELECT 1 FROM document_versions version
                       WHERE version.id = NEW.aggregate_id
                         AND version.title_snapshot = '%s'
                   ) THEN
                    RAISE EXCEPTION 'forced outbox insert failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(titleSnapshot));
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_outbox_insert
            BEFORE INSERT ON sync_outbox_events
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_outbox_insert_failure()
            """);
    }

    private void installEventCompletionFailure(UUID eventId) {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_outbox_complete_failure()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.event_id = '%s'::uuid AND NEW.status = 'PROCESSED' THEN
                    RAISE EXCEPTION 'forced outbox completion failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(eventId));
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_outbox_complete
            BEFORE UPDATE ON sync_outbox_events
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_outbox_complete_failure()
            """);
    }

    private void dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + VERSION_TRIGGER + " ON document_versions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + VERSION_FUNCTION + "()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + EVENT_INSERT_TRIGGER + " ON sync_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + EVENT_INSERT_FUNCTION + "()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + EVENT_COMPLETE_TRIGGER + " ON sync_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + EVENT_COMPLETE_FUNCTION + "()");
    }

    private void deleteDocument(DocumentUploadResponse response) {
        jdbcTemplate.update("DELETE FROM embedding_jobs WHERE document_version_id = ?", response.documentVersionId());
        jdbcTemplate.update(
            "DELETE FROM sync_outbox_events WHERE aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ?",
            response.documentVersionId()
        );
        jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", response.documentId());
        jdbcTemplate.update("DELETE FROM document_versions WHERE id = ?", response.documentVersionId());
        jdbcTemplate.update("DELETE FROM documents WHERE id = ?", response.documentId());
        jdbcTemplate.update("DELETE FROM file_objects WHERE id = ?", response.fileObjectId());
    }
}
