package com.opensource.docgrid.domain.embedding.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 현재 Embedding Job Attempt 소유권으로 문서 Chunk Embedding 생성을 요청하는 DTO.
 *
 * <p>Worker ID와 canonical UUID Claim Token은 준비와 완료 단계의 소유권 검증에만 사용하며,
 * 응답, 이벤트와 로그에는 노출하지 않는다.
 */
public record CreateDocumentEmbeddingsRequest(
    @Schema(description = "현재 Job을 소유한 Worker 식별자", example = "1")
    @NotNull
    @Positive
    Long workerId,

    @Schema(description = "현재 Claim의 canonical UUID Token",
        example = "34c19d16-6ae1-4f6a-a35d-0123456789ab")
    @NotBlank
    @Size(max = 36)
    @Pattern(
        regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        message = "canonical UUID 형식이어야 합니다."
    )
    String claimToken
) {
}
