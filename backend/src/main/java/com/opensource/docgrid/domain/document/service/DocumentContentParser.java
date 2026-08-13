package com.opensource.docgrid.domain.document.service;

import java.util.Set;

import com.opensource.docgrid.domain.document.enums.DocumentType;

/**
 * 원본 Byte를 형식별 불변 Segment 목록으로 변환하는 Parser 경계다.
 *
 * <p>구현체는 Storage, Chunk 크기와 영속화를 알지 않으며 자신이 처리하는 문서 형식과
 * 결정적인 파싱 결과만 제공한다.
 */
public interface DocumentContentParser {

    /**
     * 이 Parser가 처리할 수 있는 문서 형식을 반환한다.
     */
    Set<DocumentType> supportedTypes();

    /**
     * 원본 Byte를 Page 또는 Section 경계의 Parsed Document로 변환한다.
     */
    ParsedDocument parseDocument(byte[] content);
}
