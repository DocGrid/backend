package com.opensource.docgrid.domain.document.dto.request;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotNull;

/**
 * 기존 문서에 새 Version으로 추가할 필수 Multipart 파일 입력이다.
 */
public record DocumentVersionUploadRequest(
    @NotNull MultipartFile file
) {
}
