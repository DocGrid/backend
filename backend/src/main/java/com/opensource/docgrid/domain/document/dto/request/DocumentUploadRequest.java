package com.opensource.docgrid.domain.document.dto.request;

import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentUploadRequest(
    @NotNull
    @Schema(description = "업로드할 TXT, Markdown, PDF 또는 DOCX 파일", type = "string", format = "binary")
    MultipartFile file,

    @NotBlank
    @Size(max = 500)
    @Schema(description = "문서 제목")
    String title,

    @Schema(description = "문서 설명")
    String description,

    @NotNull
    @Schema(description = "문서 공개 범위")
    VisibilityType visibility
) {
}
