package com.opensource.docgrid.domain.document.service.query;

import com.opensource.docgrid.domain.document.storage.StoredFile;

/**
 * 원본 파일을 읽기 전에 짧은 DB Transaction에서 확정한 저장 위치와 응답 Metadata다.
 * JPA Entity를 외부 저장소 I/O 구간으로 전달하지 않는 경계 역할만 담당한다.
 */
public record DocumentFileSnapshot(
    StoredFile storedFile,
    String originalFilename,
    String contentType,
    long fileSize
) {
}
