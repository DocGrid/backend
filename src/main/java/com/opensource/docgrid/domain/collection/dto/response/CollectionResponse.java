package com.opensource.docgrid.domain.collection.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;

public record CollectionResponse(
        @Schema(description = "컬렉션 ID") Long collectionId,
        @Schema(description = "컬렉션 이름") String name,
        @Schema(description = "컬렉션 설명") String description,
        @Schema(description = "소유자 사용자 ID") Long ownerUserId,
        @Schema(description = "상위 컬렉션 ID") Long parentCollectionId,
        @Schema(description = "공개 범위") VisibilityType visibility,
        @Schema(description = "컬렉션 상태") CollectionStatus status,
        @Schema(description = "생성 시각") LocalDateTime createdAt
) {
}
