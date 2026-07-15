package com.opensource.docgrid.domain.collection.dto.request;

import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateCollectionRequest(
        @Schema(description = "컬렉션 이름") @NotBlank String name,
        @Schema(description = "컬렉션 설명") String description,
        @Schema(description = "상위 컬렉션 ID (최상위면 null)") Long parentCollectionId,
        @Schema(description = "공개 범위 (기본값 PRIVATE)", example = "PRIVATE") VisibilityType visibility
) {
}
