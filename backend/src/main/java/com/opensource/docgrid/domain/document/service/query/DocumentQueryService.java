package com.opensource.docgrid.domain.document.service.query;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.converter.DocumentStatusConverter;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentQueryService {

    private static final Set<DocumentVersionStatus> PROCESSING_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final Set<EmbeddingJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    private final DocumentRepository documentRepository;
    private final PermissionQueryService permissionQueryService;
    private final DocumentStatusConverter documentStatusConverter;

    public DocumentStatusResponse getDocumentStatus(Long userId, Long documentId) {
        if (!permissionQueryService.canReadDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        List<DocumentStatusProjection> rows = documentRepository.findDocumentStatus(
            documentId,
            PROCESSING_VERSION_STATUSES,
            ACTIVE_JOB_STATUSES
        );
        if (rows.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (rows.size() != 1 || isInconsistent(rows.get(0))) {
            log.error("문서 인덱싱 상태가 일관되지 않습니다. documentId={}, rowCount={}", documentId, rows.size());
            throw new DocGridException(ErrorCode.INDEXING_STATUS_INCONSISTENT);
        }

        DocumentStatusProjection projection = rows.get(0);
        if (projection.getDocumentStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return documentStatusConverter.toResponse(projection);
    }

    private boolean isInconsistent(DocumentStatusProjection projection) {
        boolean hasCurrentVersionNo = projection.getCurrentVersionNo() != null;
        boolean hasCurrentVersionStatus = projection.getCurrentVersionStatus() != null;
        if (hasCurrentVersionNo != hasCurrentVersionStatus) {
            return true;
        }

        boolean hasProcessingVersionNo = projection.getProcessingVersionNo() != null;
        boolean hasProcessingVersionStatus = projection.getProcessingVersionStatus() != null;
        boolean hasProcessingJobStatus = projection.getProcessingJobStatus() != null;
        return hasProcessingVersionNo != hasProcessingVersionStatus
            || hasProcessingVersionNo != hasProcessingJobStatus;
    }
}
