package com.opensource.docgrid.domain.document.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 원장 상태와 현재 검색 가능 Version 및 별도 처리 중 Version을 구분해 제공하는 응답이다.
 */
@Schema(description = "문서 인덱싱 상태")
public record DocumentStatusResponse(
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "문서 상태") DocumentStatus documentStatus,
    @Schema(description = "현재 검색 가능한 버전. 아직 검색 가능한 버전이 없으면 null", nullable = true)
    CurrentVersionStatusResponse currentVersion,
    @Schema(description = "현재 처리 중인 버전. 처리 중인 버전이 없으면 null", nullable = true)
    ProcessingVersionStatusResponse processingVersion
) {
}
