package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.CurrentDocumentVersionResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;

/**
 * 문서 Entity와 현재 버전 연관관계를 외부 상세 응답 DTO로 변환한다.
 * 저장소 Bucket과 Object Key 같은 내부 파일 위치는 변환 경계 밖으로 노출하지 않는다.
 */
@Component
public class DocumentDetailConverter {

    public DocumentDetailResponse toResponse(Document document, boolean contentAvailable) {
        return new DocumentDetailResponse(
            document.getId(),
            document.getTitle(),
            document.getDescription(),
            document.getDocumentType(),
            document.getSourceType(),
            document.getStatus(),
            document.getVisibility(),
            document.getOwner().getId(),
            document.getOwner().getName(),
            toCurrentVersionResponse(document.getCurrentVersion()),
            contentAvailable,
            document.getCreatedAt(),
            document.getUpdatedAt()
        );
    }

    private CurrentDocumentVersionResponse toCurrentVersionResponse(DocumentVersion documentVersion) {
        if (documentVersion == null) {
            return null;
        }

        FileObject fileObject = documentVersion.getFileObject();
        return new CurrentDocumentVersionResponse(
            documentVersion.getId(),
            documentVersion.getVersionNo(),
            documentVersion.getStatus(),
            documentVersion.getOriginalFilename() != null
                ? documentVersion.getOriginalFilename()
                : fileObject != null ? fileObject.getOriginalFilename() : null,
            documentVersion.getContentType() != null
                ? documentVersion.getContentType()
                : fileObject != null ? fileObject.getContentType() : null,
            fileObject != null ? fileObject.getFileSize() : null,
            documentVersion.getIndexedAt(),
            documentVersion.getCreatedAt()
        );
    }
}
