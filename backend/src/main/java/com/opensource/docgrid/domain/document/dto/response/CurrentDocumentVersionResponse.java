package com.opensource.docgrid.domain.document.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 상세 응답에서 현재 버전의 식별 정보와 원본 파일 Metadata만 노출한다.
 * 처리 중인 별도 버전이나 저장소 내부 위치는 이 응답의 책임 범위에 포함하지 않는다.
 */
@Schema(description = "문서의 현재 버전 정보")
public record CurrentDocumentVersionResponse(
    @Schema(description = "문서 버전 ID") Long documentVersionId,
    @Schema(description = "버전 번호") int versionNo,
    @Schema(description = "버전 상태") DocumentVersionStatus status,
    @Schema(description = "원본 파일명") String originalFilename,
    @Schema(description = "원본 파일 Content-Type") String contentType,
    @Schema(description = "원본 파일 크기(Byte)") Long fileSize,
    @Schema(description = "인덱싱 완료 시각") LocalDateTime indexedAt,
    @Schema(description = "버전 생성 시각") LocalDateTime createdAt
) {
}
