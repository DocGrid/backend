package com.opensource.docgrid.domain.embedding.dto.response;

import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 현재 활성 Embedding 모델의 공개 식별 정보와 Vector 계약을 제공하는 응답이다.
 *
 * <p>Provider 인증 정보나 내부 연결 주소는 제외하고 클라이언트가 모델과 거리 계산 기준을 식별할 값만 담는다.
 */
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
