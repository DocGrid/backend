package com.opensource.docgrid.domain.document.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 상세 화면에 필요한 Metadata와 현재 버전 요약을 반환한다.
 * 추출 본문과 원본 파일 Byte는 별도 API 경계로 분리해 이 응답에 포함하지 않는다.
 */
@Schema(description = "문서 상세 정보")
public record DocumentDetailResponse(
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "문서 제목") String title,
    @Schema(description = "문서 설명") String description,
    @Schema(description = "문서 형식") DocumentType documentType,
    @Schema(description = "문서 생성 출처") DocumentSourceType sourceType,
    @Schema(description = "문서 상태") DocumentStatus status,
    @Schema(description = "공개 범위") VisibilityType visibility,
    @Schema(description = "소유자 사용자 ID") Long ownerUserId,
    @Schema(description = "소유자 이름") String ownerName,
    @Schema(description = "현재 버전 정보, 현재 버전이 없으면 null") CurrentDocumentVersionResponse currentVersion,
    @Schema(description = "현재 버전의 추출 본문 조회 가능 여부") boolean contentAvailable,
    @Schema(description = "문서 생성 시각") LocalDateTime createdAt,
    @Schema(description = "문서 마지막 수정 시각") LocalDateTime updatedAt
) {
}
