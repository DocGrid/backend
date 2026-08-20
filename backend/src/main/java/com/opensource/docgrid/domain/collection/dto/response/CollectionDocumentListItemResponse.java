package com.opensource.docgrid.domain.collection.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 컬렉션 문서 목록에서 문서 Metadata와 컬렉션 추가 이력을 함께 노출하는 응답이다.
 * 개별 문서 읽기 권한을 통과한 문서만 이 응답으로 변환된다.
 */
@Schema(description = "컬렉션에 포함된 읽기 가능한 문서")
public record CollectionDocumentListItemResponse(
    @Schema(description = "컬렉션 ID") Long collectionId,
    @Schema(description = "문서 Metadata와 현재 버전 요약") DocumentSummaryResponse document,
    @Schema(description = "컬렉션에 문서를 추가한 사용자 ID") Long addedBy,
    @Schema(description = "컬렉션에 문서를 추가한 사용자 이름") String addedByName,
    @Schema(description = "컬렉션에 문서를 추가한 시각") LocalDateTime addedAt
) {
}
