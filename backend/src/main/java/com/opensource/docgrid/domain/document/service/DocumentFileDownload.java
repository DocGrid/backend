package com.opensource.docgrid.domain.document.service;

/**
 * 권한 검증을 마친 문서 원본 Byte와 HTTP 응답에 필요한 파일 Metadata를 함께 전달한다.
 * Content-Disposition 선택은 HTTP 계층의 책임이므로 이 값에 포함하지 않는다.
 */
public record DocumentFileDownload(
    byte[] content,
    String originalFilename,
    String contentType,
    long fileSize
) {
}
