package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.CurrentVersionStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.ProcessingVersionStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;

@Component
public class DocumentStatusConverter {

    public DocumentStatusResponse toResponse(DocumentStatusProjection projection) {
        CurrentVersionStatusResponse currentVersion = null;
        if (projection.getCurrentVersionStatus() == DocumentVersionStatus.INDEXED) {
            currentVersion = new CurrentVersionStatusResponse(
                projection.getCurrentVersionNo(),
                projection.getCurrentVersionStatus()
            );
        }

        ProcessingVersionStatusResponse processingVersion = null;
        if (projection.getProcessingVersionNo() != null) {
            processingVersion = new ProcessingVersionStatusResponse(
                projection.getProcessingVersionNo(),
                projection.getProcessingVersionStatus(),
                projection.getProcessingJobStatus()
            );
        }

        return new DocumentStatusResponse(
            projection.getDocumentId(),
            projection.getDocumentStatus(),
            currentVersion,
            processingVersion
        );
    }
}
