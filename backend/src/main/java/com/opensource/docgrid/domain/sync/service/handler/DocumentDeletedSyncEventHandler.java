package com.opensource.docgrid.domain.sync.service.handler;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.service.SyncEventHandler;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Soft-delete된 문서의 ACTIVE Vector를 STALE로 전환해 검색에서 즉시 제외한다.
 *
 * <p>문서 원장 상태가 DELETED인지 다시 확인하며 Chunk와 Vector의 물리 삭제는 수행하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DocumentDeletedSyncEventHandler implements SyncEventHandler {

    private final DocumentRepository documentRepository;
    private final EmbeddingRepository embeddingRepository;

    /** 이 Handler가 문서 삭제 Event만 처리함을 선언한다. */
    @Override
    public Set<SyncEventType> supportedTypes() {
        return Set.of(SyncEventType.DOCUMENT_DELETED);
    }

    /**
     * 삭제 원장 상태를 확인한 뒤 해당 문서의 ACTIVE Vector를 검색 제외 상태로 일괄 전환한다.
     */
    @Override
    public void handle(SyncOutboxEvent event) {
        // 1. Payload가 아닌 Aggregate ID로 최신 Document 원장을 다시 조회한다.
        Document document = documentRepository.findById(event.getAggregateId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));

        // 2. 상태와 삭제 시각이 모두 확정된 실제 Soft-delete Event인지 검증한다.
        if (document.getStatus() != DocumentStatus.DELETED || document.getDeletedAt() == null) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }

        // 3. 원본 이력은 보존하고 검색에 쓰이는 ACTIVE Vector만 멱등하게 STALE로 변경한다.
        embeddingRepository.markActiveAsStaleByDocumentId(document.getId());
    }
}
