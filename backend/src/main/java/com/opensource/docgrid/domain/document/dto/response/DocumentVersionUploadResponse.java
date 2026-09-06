package com.opensource.docgrid.domain.document.dto.response;

import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 기존 문서에 새 Version을 업로드한 결과와 유지되는 현재 검색 Version 정보를 함께 반환한다.
 *
 * <p>새 Version이 인덱싱을 마칠 때까지 currentVersionId는 기존 검색 가능 Version을 계속 가리킬 수 있다.
 */
public record DocumentVersionUploadResponse(
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "생성된 문서 버전 ID") Long documentVersionId,
    @Schema(description = "버전 번호") int versionNo,
    @Schema(description = "생성된 임베딩 작업 ID") Long embeddingJobId,
    @Schema(description = "현재 활성 버전 ID") Long currentVersionId,
    @Schema(description = "문서 상태") DocumentStatus documentStatus,
    @Schema(description = "새 버전 상태") DocumentVersionStatus versionStatus,
    @Schema(description = "임베딩 작업 상태") EmbeddingJobStatus jobStatus
) {
}
