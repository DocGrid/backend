package com.opensource.docgrid.domain.document.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 목록에서 원장 상태와 현재 검색 가능한 Version을 함께 보여주는 요약 응답이다.
 *
 * <p>아직 인덱싱 완료 Version이 없는 문서는 currentVersion 필드가 {@code null}이며 처리 중 상태는 별도
 * 문서 상태 API에서 조회한다.
 */
public record DocumentSummaryResponse(
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "문서 제목") String title,
    @Schema(description = "문서 설명") String description,
    @Schema(description = "문서 형식", example = "PDF") DocumentType documentType,
    @Schema(description = "문서 상태", example = "INDEXED") DocumentStatus status,
    @Schema(description = "공개 범위", example = "PRIVATE") VisibilityType visibility,
    @Schema(description = "소유자 사용자 ID") Long ownerUserId,
    @Schema(description = "소유자 이름") String ownerName,
    @Schema(description = "현재 활성 버전 번호, 아직 활성 버전이 없으면 null") Integer currentVersionNo,
    @Schema(description = "현재 활성 버전 상태, 아직 활성 버전이 없으면 null") DocumentVersionStatus currentVersionStatus,
    @Schema(description = "등록 시각") LocalDateTime createdAt,
    @Schema(description = "마지막 수정 시각") LocalDateTime updatedAt
) {
}
