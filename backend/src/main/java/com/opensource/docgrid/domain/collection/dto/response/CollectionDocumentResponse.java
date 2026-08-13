package com.opensource.docgrid.domain.collection.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "컬렉션 문서 추가 결과")
public record CollectionDocumentResponse(
        @Schema(description = "컬렉션 ID") Long collectionId,
        @Schema(description = "문서 ID") Long documentId,
        @Schema(description = "추가한 사용자 ID") Long addedBy,
        @Schema(description = "추가 시각") LocalDateTime addedAt
) {
}
