package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.enums.SyncPermissionOperation;
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

    /**
     * 권한 원장 변경 뒤 사용자 접근 캐시를 다시 맞출 작업을 기록한다.
     */
    public SyncOutboxEvent recordPermissionCacheRefresh(
        AccessSourceType sourceType,
        Long sourceId,
        SyncPermissionOperation operation
    ) {
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        String idempotencyKey = String.format(
            "%s:%s:%d:%s:%s",
            SyncAggregateType.PERMISSION,
            sourceType,
            sourceId,
            SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED,
            operation
        );
        String payloadJson = String.format(
            "{\"sourceType\":\"%s\",\"operation\":\"%s\"}",
            sourceType,
            operation
        );
        return saveEvent(
            idempotencyKey,
            SyncAggregateType.PERMISSION,
            sourceId,
            null,
            SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED,
            payloadJson,
            occurredAt
        );
    }

    /**
     * Reconciler나 모델 전환 흐름이 지정 Version의 재인덱싱을 멱등 요청한다.
     */
    public SyncOutboxEvent recordDocumentReindexRequested(
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        String requestKey
    ) {
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        String idempotencyKey = String.format(
            "%s:%d:%s:%d:%s",
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            SyncEventType.DOCUMENT_REINDEX_REQUESTED,
            embeddingModel.getId(),
            requestKey
        );
        return saveEvent(
            idempotencyKey,
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            (long) documentVersion.getVersionNo(),
            SyncEventType.DOCUMENT_REINDEX_REQUESTED,
            String.format("{\"embeddingModelId\":%d}", embeddingModel.getId()),
            occurredAt
        );
    }

    private SyncOutboxEvent saveEvent(
        String idempotencyKey,
        SyncAggregateType aggregateType,
        Long aggregateId,
        Long aggregateVersion,
        SyncEventType eventType,
        String payloadJson,
        LocalDateTime occurredAt
    ) {
        return syncOutboxEventRepository.save(
            SyncOutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .aggregateVersion(aggregateVersion)
                .eventType(eventType)
                .payloadJson(payloadJson)
                .occurredAt(occurredAt)
                .availableAt(occurredAt)
                .maxRetryCount(DEFAULT_MAX_RETRY_COUNT)
                .build()
        );
    }
}
