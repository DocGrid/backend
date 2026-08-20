package com.opensource.docgrid.domain.collection.dto.request;

import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 컬렉션의 공개 범위를 변경하는 요청이다.
 *
 * <p>{@code COLLECTION}, {@code DEPARTMENT}는 아직 접근판정에 반영되지 않는 값이라 지원하지 않는다.
 */
public record UpdateCollectionVisibilityRequest(
    @NotNull
    @Schema(description = "변경할 공개 범위 (PRIVATE 또는 PUBLIC만 허용)", example = "PUBLIC")
    VisibilityType visibility
) {
}
