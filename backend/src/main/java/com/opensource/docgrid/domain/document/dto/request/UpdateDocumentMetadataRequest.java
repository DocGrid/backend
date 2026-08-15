package com.opensource.docgrid.domain.document.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문서의 현재 표시 제목과 설명을 함께 교체하는 요청이다.
 */
public record UpdateDocumentMetadataRequest(
    @NotBlank
    @Size(max = 500)
    @Schema(description = "변경할 문서 제목", maxLength = 500)
    String title,

    @Schema(description = "변경할 문서 설명, null 또는 공백이면 설명을 제거합니다.", nullable = true)
    String description
) {
}
