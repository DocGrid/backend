package com.opensource.docgrid.domain.embedding.dto.response;

import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmbeddingModelResponse(
    @Schema(description = "임베딩 모델 식별자", example = "1")
    Long id,

    @Schema(description = "모델 제공 또는 실행 방식", example = "MOCK")
    EmbeddingProvider provider,

    @Schema(description = "모델 이름", example = "mock-bge-m3")
    String modelName,

    @Schema(description = "모델 버전 또는 Revision", example = "v1")
    String modelVersion,

    @Schema(description = "모델이 생성하는 Vector 차원", example = "1024")
    int dimension,

    @Schema(description = "Vector 유사도 계산 방식", example = "COSINE")
    DistanceMetric distanceMetric
) {
}
