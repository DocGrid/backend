package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionHistoryResponse;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

/**
 * 문서 버전과 그 버전의 최신 Job을 외부 타임라인 응답으로 결합한다.
 * 파일 저장소 위치와 Job의 오류 상세문은 의도적으로 노출하지 않는다.
 */
@Component
public class DocumentVersionHistoryConverter {

    public DocumentVersionHistoryResponse toResponse(
        DocumentVersion version,
        Long currentVersionId,
        EmbeddingJob latestJob
    ) {
        FileObject fileObject = version.getFileObject();
        return new DocumentVersionHistoryResponse(
            version.getId(),
            version.getVersionNo(),
            version.getStatus(),
            version.getId().equals(currentVersionId),
            version.getOriginalFilename() != null
                ? version.getOriginalFilename()
                : fileObject != null ? fileObject.getOriginalFilename() : null,
            version.getContentType() != null
                ? version.getContentType()
                : fileObject != null ? fileObject.getContentType() : null,
            fileObject != null ? fileObject.getFileSize() : null,
            version.getFileHash() != null
                ? version.getFileHash()
                : fileObject != null ? fileObject.getFileHash() : null,
            version.getCreatedBy() != null ? version.getCreatedBy().getId() : null,
            version.getCreatedBy() != null ? version.getCreatedBy().getName() : null,
            version.getCreatedAt(),
            version.getIndexedAt(),
            latestJob != null ? latestJob.getId() : null,
            latestJob != null ? latestJob.getStatus() : null,
            latestJob != null && latestJob.getLockedByWorker() != null
                ? latestJob.getLockedByWorker().getWorkerName()
                : null,
            latestJob != null ? latestJob.getErrorCode() : null,
            latestJob != null ? latestJob.getRetryCount() : null,
            latestJob != null ? latestJob.getMaxRetryCount() : null
        );
    }
}
