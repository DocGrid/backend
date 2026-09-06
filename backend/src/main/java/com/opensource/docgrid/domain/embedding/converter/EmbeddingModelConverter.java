package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;

/**
 * EmbeddingModel Entity에서 공개 가능한 모델 식별 정보와 Vector 계약만 응답으로 변환한다.
 */
@Component
public class EmbeddingModelConverter {

    /** Provider 비밀값과 내부 설정을 제외한 활성 모델 조회 응답을 만든다. */
    public EmbeddingModelResponse toResponse(EmbeddingModel embeddingModel) {
        return new EmbeddingModelResponse(
            embeddingModel.getId(),
            embeddingModel.getProvider(),
            embeddingModel.getModelName(),
            embeddingModel.getModelVersion(),
            embeddingModel.getDimension(),
            embeddingModel.getDistanceMetric()
        );
    }
}
