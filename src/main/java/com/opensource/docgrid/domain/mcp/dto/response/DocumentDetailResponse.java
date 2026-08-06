package com.opensource.docgrid.domain.mcp.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record DocumentDetailResponse(
        @Schema(description = "문서 ID") Long documentId,
        @Schema(description = "문서 제목") String title,
        @Schema(description = "현재 버전 번호 - 아직 확정된 버전이 없으면 null", nullable = true) Integer currentVersionNo,
        @Schema(description = "문서 상태") DocumentStatus status,
        @Schema(description = "마지막 수정 시각") LocalDateTime updatedAt
) {
}
