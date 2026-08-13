package com.opensource.docgrid.domain.document.dto.request;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotNull;

public record DocumentVersionUploadRequest(
    @NotNull MultipartFile file
) {
}
