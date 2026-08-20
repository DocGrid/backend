package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;

@Component
public class DocumentSummaryConverter {

    public DocumentSummaryResponse toResponse(Document document) {
        DocumentVersion currentVersion = document.getCurrentVersion();

        return new DocumentSummaryResponse(
            document.getId(),
            document.getTitle(),
            document.getDescription(),
            document.getDocumentType(),
            document.getStatus(),
            document.getVisibility(),
            document.getOwner().getId(),
            document.getOwner().getName(),
            currentVersion == null ? null : currentVersion.getVersionNo(),
            currentVersion == null ? null : currentVersion.getStatus(),
            document.getCreatedAt(),
            document.getUpdatedAt()
        );
    }
}
