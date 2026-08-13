package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
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
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

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
        return saveEvent(
            idempotencyKey,
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            (long) documentVersion.getVersionNo(),
            SyncEventType.DOCUMENT_VERSION_CREATED,
            payloadJson,
            occurredAt
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
        // 1. DB Unique Key와 ON CONFLICT를 한 문장으로 실행해 동시 요청도 예외 없이 한 행에 수렴시킨다.
        syncOutboxEventRepository.insertPendingIfAbsent(
            UUID.randomUUID(),
            idempotencyKey,
            aggregateType.name(),
            aggregateId,
            aggregateVersion,
            eventType.name(),
            payloadJson,
            occurredAt,
            occurredAt,
            DEFAULT_MAX_RETRY_COUNT
        );

        // 2. 최초 생성자와 중복 요청자 모두 DB가 선택한 동일 Event를 반환한다.
        SyncOutboxEvent event = syncOutboxEventRepository.findByIdempotencyKey(idempotencyKey)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        validateExistingEvent(event, aggregateType, aggregateId, aggregateVersion, eventType, payloadJson);
        return event;
    }

    private void validateExistingEvent(
        SyncOutboxEvent event,
        SyncAggregateType aggregateType,
        Long aggregateId,
        Long aggregateVersion,
        SyncEventType eventType,
        String payloadJson
    ) {
        // 같은 Key가 다른 명령을 가리키면 중복 성공으로 숨기지 않고 원장 충돌로 중단한다.
        if (event.getAggregateType() != aggregateType
            || !Objects.equals(event.getAggregateId(), aggregateId)
            || !Objects.equals(event.getAggregateVersion(), aggregateVersion)
            || event.getEventType() != eventType
            || !Objects.equals(event.getPayloadJson(), payloadJson)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
