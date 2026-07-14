package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;

@Component
public class EmbeddingModelConverter {

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
