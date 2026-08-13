package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

/**
 * SyncEventWriter가 문서 버전 변경을 재현 가능한 멱등 Outbox Event로 변환하는지 검증한다.
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
        given(syncOutboxEventRepository.save(any(SyncOutboxEvent.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        SyncOutboxEvent result = syncEventWriter.recordDocumentVersionCreated(version, model);

        ArgumentCaptor<SyncOutboxEvent> eventCaptor = ArgumentCaptor.forClass(SyncOutboxEvent.class);
        then(syncOutboxEventRepository).should().save(eventCaptor.capture());
        assertThat(result).isSameAs(eventCaptor.getValue());
        assertThat(result.getEventId()).isNotNull();
        assertThat(result.getIdempotencyKey())
            .isEqualTo("DOCUMENT_VERSION:341:DOCUMENT_VERSION_CREATED:3");
        assertThat(result.getAggregateType()).isEqualTo(SyncAggregateType.DOCUMENT_VERSION);
        assertThat(result.getAggregateId()).isEqualTo(341L);
        assertThat(result.getAggregateVersion()).isEqualTo(3L);
        assertThat(result.getEventType()).isEqualTo(SyncEventType.DOCUMENT_VERSION_CREATED);
        assertThat(result.getPayloadJson()).isEqualTo("{\"embeddingModelId\":7}");
        assertThat(result.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertThat(result.getAvailableAt()).isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID));
        assertThat(result.getOccurredAt()).isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE_ID));
        assertThat(result.getRetryCount()).isZero();
        assertThat(result.getMaxRetryCount()).isEqualTo(5);
    }
}
