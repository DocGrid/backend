package com.opensource.docgrid.domain.document.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;

/**
 * Document 원장과 선택적 현재 Version을 문서 목록용 요약 응답으로 변환한다.
 *
 * <p>Entity 자체를 Controller에 노출하지 않고 소유자와 현재 검색 Version의 필요한 필드만 평탄화한다.
 */
@Component
public class DocumentSummaryConverter {

    /** 문서와 현재 INDEXED Version 정보를 목록 응답으로 조합한다. */
    public DocumentSummaryResponse toResponse(Document document) {
        // 1. 아직 최초 인덱싱이 끝나지 않은 문서에는 현재 Version이 없을 수 있다.
        DocumentVersion currentVersion = document.getCurrentVersion();

        // 2. Transaction 안에서 필요한 LAZY 관계 값을 읽어 Entity와 분리된 응답을 완성한다.
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
