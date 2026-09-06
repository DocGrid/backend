package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
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
        // 1. 도메인 변경과 같은 Clock 기준으로 Event 발생 시각을 고정한다.
        LocalDateTime occurredAt = LocalDateTime.now(clock);

        // 2. 버전별 생성 Event가 재호출돼도 같은 논리 Event에 수렴하도록 결정적 Key를 만든다.
        String idempotencyKey = String.format(
            "%s:%d:%s:%d",
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            SyncEventType.DOCUMENT_VERSION_CREATED,
            documentVersion.getVersionNo()
        );
        String payloadJson = String.format("{\"embeddingModelId\":%d}", embeddingModel.getId());

        // 3. Version·Job 생성 Transaction과 함께 저장해 Dispatcher가 부분 상태를 관측하지 않게 한다.
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
     * Soft-delete된 문서의 검색 Vector를 비활성화할 의도를 같은 Transaction에 기록한다.
     */
    public SyncOutboxEvent recordDocumentDeleted(Document document) {
        // 1. 삭제 상태 전이와 동일한 Event 발생 시각을 기록한다.
        LocalDateTime occurredAt = LocalDateTime.now(clock);

        // 2. 한 문서의 삭제 의도가 여러 번 기록돼도 하나의 Event만 남도록 Key를 생성한다.
        String idempotencyKey = String.format(
            "%s:%d:%s",
            SyncAggregateType.DOCUMENT,
            document.getId(),
            SyncEventType.DOCUMENT_DELETED
        );

        // 3. 별도 Payload가 필요 없는 삭제 Event를 호출 Transaction에 저장한다.
        return saveEvent(
            idempotencyKey,
            SyncAggregateType.DOCUMENT,
            document.getId(),
            null,
            SyncEventType.DOCUMENT_DELETED,
            "{}",
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
        // 1. 권한 원장 변경과 함께 기록할 Event 발생 시각을 고정한다.
        LocalDateTime occurredAt = LocalDateTime.now(clock);

        // 2. 권한 출처와 작업 종류까지 Key에 포함해 부여와 회수를 서로 다른 의도로 구분한다.
        String idempotencyKey = String.format(
            "%s:%s:%d:%s:%s",
            SyncAggregateType.PERMISSION,
            sourceType,
            sourceId,
            SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED,
            operation
        );

        // 3. Handler가 원장 종류와 갱신 방향을 재구성할 최소 Payload를 만든다.
        String payloadJson = String.format(
            "{\"sourceType\":\"%s\",\"operation\":\"%s\"}",
            sourceType,
            operation
        );

        // 4. 권한 변경과 같은 Transaction에 캐시 재투영 의도를 저장한다.
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
        // 1. 복구 또는 모델 전환 명령과 공유할 Event 발생 시각을 고정한다.
        LocalDateTime occurredAt = LocalDateTime.now(clock);

        // 2. 호출 목적의 requestKey를 포함해 같은 재인덱싱 명령만 멱등 처리한다.
        String idempotencyKey = String.format(
            "%s:%d:%s:%d:%s",
            SyncAggregateType.DOCUMENT_VERSION,
            documentVersion.getId(),
            SyncEventType.DOCUMENT_REINDEX_REQUESTED,
            embeddingModel.getId(),
            requestKey
        );

        // 3. 대상 버전·모델을 재구성할 Event를 호출 Transaction에 저장한다.
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

    /**
     * 논리 Event를 멱등 Insert하고 DB가 선택한 기존 또는 신규 행의 내용이 요청과 같은지 검증한다.
     *
     * <p>애플리케이션의 사전 조회 없이 {@code ON CONFLICT}를 사용하므로 동시 요청도 유일 Key 위반
     * 예외 없이 하나의 Outbox 행에 수렴한다.
     */
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

    /**
     * 같은 멱등 Key로 조회된 Event가 동일한 Aggregate·Version·Type·Payload를 표현하는지 확인한다.
     *
     * <p>Key 충돌이 다른 명령을 가리키면 성공으로 숨기지 않고 원장 불일치로 중단한다.
     */
    private void validateExistingEvent(
        SyncOutboxEvent event,
        SyncAggregateType aggregateType,
        Long aggregateId,
        Long aggregateVersion,
        SyncEventType eventType,
        String payloadJson
    ) {
        if (event.getAggregateType() != aggregateType
            || !Objects.equals(event.getAggregateId(), aggregateId)
            || !Objects.equals(event.getAggregateVersion(), aggregateVersion)
            || event.getEventType() != eventType
            || !Objects.equals(event.getPayloadJson(), payloadJson)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
