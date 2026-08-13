package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * 도메인 변경 Transaction 안에서 후속 동기화 Outbox Event를 기록한다.
 *
 * <p>이 Service는 Event를 외부로 발행하지 않는다. 호출한 Command Transaction과 같은 경계에서 Event를
 * 저장해 도메인 변경만 Commit되거나 Event만 남는 이중 쓰기 상태를 방지하는 것이 책임이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncEventWriter {

    private static final int DEFAULT_MAX_RETRY_COUNT = 5;

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final Clock clock;

    /**
     * 새 문서 버전의 최초 인덱싱 의도를 같은 Transaction에 기록한다.
     */
    public SyncOutboxEvent recordDocumentVersionCreated(
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel
    ) {
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        String idempotencyKey = String.format(
            "%s:%d:%s:%d",
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            SyncEventType.DOCUMENT_VERSION_CREATED,
            documentVersion.getVersionNo()
        );
        String payloadJson = String.format("{\"embeddingModelId\":%d}", embeddingModel.getId());

        // Version·Job 생성 Transaction과 함께 Commit돼야 Dispatcher가 부분 상태를 관측하지 않는다.
        return syncOutboxEventRepository.save(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
                .aggregateId(documentVersion.getId())
                .aggregateVersion((long) documentVersion.getVersionNo())
                .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
                .payloadJson(payloadJson)
                .occurredAt(occurredAt)
                .availableAt(occurredAt)
                .maxRetryCount(DEFAULT_MAX_RETRY_COUNT)
                .build()
        );
    }
}
