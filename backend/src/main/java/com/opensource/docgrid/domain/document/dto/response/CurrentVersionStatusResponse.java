package com.opensource.docgrid.domain.document.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서가 현재 검색 대상으로 제공하는 INDEXED Version의 번호와 상태를 나타낸다.
 */
@Schema(description = "현재 검색 가능한 문서 버전 상태")
public record CurrentVersionStatusResponse(
    @Schema(description = "문서 버전 번호") int versionNo,
    @Schema(description = "문서 버전 상태", example = "INDEXED") DocumentVersionStatus status
) {
}
