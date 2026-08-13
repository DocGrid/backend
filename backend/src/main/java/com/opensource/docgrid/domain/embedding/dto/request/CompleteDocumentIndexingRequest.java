package com.opensource.docgrid.domain.embedding.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 현재 Embedding Job Attempt의 소유권으로 문서 인덱싱 완료를 확정하는 요청 DTO.
 *
 * <p>Worker ID와 Claim Token은 최초 완료 및 멱등 재생의 실행 식별에만 사용하며 응답에는 노출하지 않는다.
 */
public record CompleteDocumentIndexingRequest(
    @Schema(description = "현재 Job을 소유한 Worker 식별자", example = "7")
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
