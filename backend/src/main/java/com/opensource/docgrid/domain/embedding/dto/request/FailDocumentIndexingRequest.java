package com.opensource.docgrid.domain.embedding.dto.request;

import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 현재 Embedding Job Attempt의 소유권으로 문서 인덱싱 실패를 보고하는 요청 DTO.
 *
 * <p>호출자는 제한된 실패 유형과 진단 메시지만 전달하며, Retry 여부와 다음 실행 시각은 서버 정책이
 * 결정한다. Claim Token과 오류 메시지는 실패 응답에 다시 노출하지 않는다.
 */
public record FailDocumentIndexingRequest(
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
    String claimToken,

    @Schema(description = "서버 Retry 정책에 연결되는 인덱싱 실패 유형",
        example = "EMBEDDING_PROVIDER_UNAVAILABLE")
    @NotNull
    IndexingFailureType failureType,

    @Schema(description = "비밀정보와 원문을 제외한 진단 메시지", example = "Embedding provider request timed out")
    @NotBlank
    @Size(max = 2000)
    String errorMessage
) {
}
