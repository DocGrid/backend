package com.opensource.docgrid.domain.collection.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record AddDocumentRequest(
        @Schema(description = "추가할 문서 ID") @NotNull Long documentId
) {
}
