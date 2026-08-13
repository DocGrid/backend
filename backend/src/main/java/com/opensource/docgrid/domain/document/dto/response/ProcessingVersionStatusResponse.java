package com.opensource.docgrid.domain.document.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 처리 중인 문서 버전과 임베딩 작업 상태")
public record ProcessingVersionStatusResponse(
    @Schema(description = "문서 버전 번호") int versionNo,
    @Schema(description = "문서 버전 상태", example = "PARSING") DocumentVersionStatus status,
    @Schema(description = "임베딩 작업 상태", example = "PROCESSING") EmbeddingJobStatus jobStatus
) {
}
