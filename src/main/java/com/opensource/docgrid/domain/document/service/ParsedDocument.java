package com.opensource.docgrid.domain.document.service;

import java.util.List;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 원본 순서를 보존한 문서 Segment의 불변 집합이다.
 *
 * <p>Chunk 계산 전 경계로만 사용하며 Storage 위치, Worker 소유권과 JPA Entity를 포함하지 않는다.
 */
public record ParsedDocument(List<ParsedDocumentSegment> segments) {

    public ParsedDocument {
        if (segments == null || segments.isEmpty() || segments.stream().anyMatch(segment -> segment == null)) {
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
        }
        segments = List.copyOf(segments);
    }

    /**
     * Page·Section Metadata가 없는 단일 Text Segment 문서를 만든다.
     */
    public static ParsedDocument single(String text) {
        return new ParsedDocument(List.of(new ParsedDocumentSegment(text, null, null, null)));
    }
}
