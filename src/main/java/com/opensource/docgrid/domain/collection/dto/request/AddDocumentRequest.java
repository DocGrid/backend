package com.opensource.docgrid.domain.collection.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "컬렉션 문서 추가 요청")
public record AddDocumentRequest(
        @Schema(description = "추가할 문서 ID") @NotNull Long documentId
) {
}
