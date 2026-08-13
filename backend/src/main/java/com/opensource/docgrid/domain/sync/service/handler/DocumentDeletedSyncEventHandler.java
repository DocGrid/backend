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

    @Override
    public Set<SyncEventType> supportedTypes() {
        return Set.of(SyncEventType.DOCUMENT_DELETED);
    }

    @Override
    public void handle(SyncOutboxEvent event) {
        Document document = documentRepository.findById(event.getAggregateId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));
        if (document.getStatus() != DocumentStatus.DELETED || document.getDeletedAt() == null) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
        embeddingRepository.markActiveAsStaleByDocumentId(document.getId());
    }
}
