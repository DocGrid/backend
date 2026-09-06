package com.opensource.docgrid.domain.document.service;

import com.opensource.docgrid.domain.document.enums.DocumentType;

/**
 * 업로드 파일의 이름·형식·크기 검증을 통과한 뒤 후속 Hash·저장 단계가 재사용하는 불변 Snapshot이다.
 */
public record ValidatedFile(
    String originalFilename,
    String extension,
    String contentType,
    long fileSize,
    DocumentType documentType
) {
}
