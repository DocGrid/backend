package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

/**
 * SyncEventWriter가 문서 버전 생성과 문서 삭제를 재현 가능한 멱등 Outbox Event로 변환하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventWriter 단위 테스트")
class SyncEventWriterTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T06:00:00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock private SyncOutboxEventRepository syncOutboxEventRepository;

    private SyncEventWriter syncEventWriter;

    @BeforeEach
    void setUp() {
        syncEventWriter = new SyncEventWriter(
            syncOutboxEventRepository,
            Clock.fixed(FIXED_INSTANT, ZONE_ID)
        );
    }

    @Test
    @DisplayName("문서 버전과 모델을 PENDING Outbox Event로 기록한다")
    void recordDocumentVersionCreated_savesPendingEvent() {
        DocumentVersion version = DocumentVersion.builder()
            .versionNo(3)
            .status(DocumentVersionStatus.UPLOADED)
            .build();
        ReflectionTestUtils.setField(version, "id", 341L);
        EmbeddingModel model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);
        LocalDateTime occurredAt = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID);
        SyncOutboxEvent persisted = SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("DOCUMENT_VERSION:341:DOCUMENT_VERSION_CREATED:3")
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(341L)
            .aggregateVersion(3L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{\"embeddingModelId\":7}")
            .availableAt(occurredAt)
            .occurredAt(occurredAt)
            .maxRetryCount(5)
            .build();
        given(syncOutboxEventRepository.insertPendingIfAbsent(
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            any(Long.class),
            any(String.class),
            any(String.class),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyInt()
        )).willReturn(1);
        given(syncOutboxEventRepository.findByIdempotencyKey(persisted.getIdempotencyKey()))
            .willReturn(Optional.of(persisted));

        SyncOutboxEvent result = syncEventWriter.recordDocumentVersionCreated(version, model);

        then(syncOutboxEventRepository).should().insertPendingIfAbsent(
            any(UUID.class),
            eq("DOCUMENT_VERSION:341:DOCUMENT_VERSION_CREATED:3"),
            eq("DOCUMENT_VERSION"),
            eq(341L),
            eq(3L),
            eq("DOCUMENT_VERSION_CREATED"),
            eq("{\"embeddingModelId\":7}"),
            eq(occurredAt),
            eq(occurredAt),
            eq(5)
        );
        assertThat(result).isSameAs(persisted);
        assertThat(result.getEventId()).isNotNull();
        assertThat(result.getIdempotencyKey())
            .isEqualTo("DOCUMENT_VERSION:341:DOCUMENT_VERSION_CREATED:3");
        assertThat(result.getAggregateType()).isEqualTo(SyncAggregateType.DOCUMENT_VERSION);
        assertThat(result.getAggregateId()).isEqualTo(341L);
        assertThat(result.getAggregateVersion()).isEqualTo(3L);
        assertThat(result.getEventType()).isEqualTo(SyncEventType.DOCUMENT_VERSION_CREATED);
        assertThat(result.getPayloadJson()).isEqualTo("{\"embeddingModelId\":7}");
        assertThat(result.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertThat(result.getAvailableAt()).isEqualTo(occurredAt);
        assertThat(result.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(result.getRetryCount()).isZero();
        assertThat(result.getMaxRetryCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("삭제된 문서를 DOCUMENT_DELETED Outbox Event로 기록한다")
    void recordDocumentDeleted_savesPendingEvent() {
        Document document = Document.builder()
            .title("삭제 문서")
            .status(DocumentStatus.INDEXED)
            .build();
        ReflectionTestUtils.setField(document, "id", 52L);
        document.markDeleted(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID));
        LocalDateTime occurredAt = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID);
        SyncOutboxEvent persisted = SyncOutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .idempotencyKey("DOCUMENT:52:DOCUMENT_DELETED")
            .aggregateType(SyncAggregateType.DOCUMENT)
            .aggregateId(52L)
            .eventType(SyncEventType.DOCUMENT_DELETED)
            .payloadJson("{}")
            .availableAt(occurredAt)
            .occurredAt(occurredAt)
            .maxRetryCount(5)
            .build();
        given(syncOutboxEventRepository.insertPendingIfAbsent(
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(Long.class),
            isNull(),
            any(String.class),
            any(String.class),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyInt()
        )).willReturn(1);
        given(syncOutboxEventRepository.findByIdempotencyKey(persisted.getIdempotencyKey()))
            .willReturn(Optional.of(persisted));

        SyncOutboxEvent result = syncEventWriter.recordDocumentDeleted(document);

        then(syncOutboxEventRepository).should().insertPendingIfAbsent(
            any(UUID.class),
            eq("DOCUMENT:52:DOCUMENT_DELETED"),
            eq("DOCUMENT"),
            eq(52L),
            isNull(),
            eq("DOCUMENT_DELETED"),
            eq("{}"),
            eq(occurredAt),
            eq(occurredAt),
            eq(5)
        );
        assertThat(result).isSameAs(persisted);
        assertThat(result.getAggregateType()).isEqualTo(SyncAggregateType.DOCUMENT);
        assertThat(result.getAggregateId()).isEqualTo(52L);
        assertThat(result.getAggregateVersion()).isNull();
        assertThat(result.getEventType()).isEqualTo(SyncEventType.DOCUMENT_DELETED);
    }
}
