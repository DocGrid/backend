package com.opensource.docgrid.domain.document.service;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Chunk가 넘지 않아야 할 Page 또는 Section 단위 Text와 출처 Metadata를 나타낸다.
 *
 * <p>Text는 Parser가 Canonical 형태로 제공하며 Page Number는 PDF에서만, Section Title은
 * 구조화된 문서에서만 사용한다.
 */
public record ParsedDocumentSegment(
    String text,
    Integer pageNo,
    String sectionTitle,
    String metadataJson
) {

    public ParsedDocumentSegment {
        if (text == null || text.isBlank()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
        }
        if (pageNo != null && pageNo <= 0) {
            throw new IllegalArgumentException("Page Number는 양수여야 합니다.");
        }
        sectionTitle = normalizeOptional(sectionTitle);
        metadataJson = normalizeOptional(metadataJson);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
