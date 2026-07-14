package com.opensource.docgrid.domain.embedding.fixture;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.enums.VectorStorageStrategy;

public class EmbeddingModelFixture {

    public static final String MODEL_NAME = "mock-bge-m3";
    public static final String MODEL_VERSION = "v1";
    public static final int DIMENSION = 1024;

    private EmbeddingModelFixture() {
    }

    public static EmbeddingModel createDefaultModel() {
        return createModel(MODEL_NAME, true, true);
    }

    public static EmbeddingModel createModel(String modelName, boolean isActive, boolean isSearchable) {
        return EmbeddingModel.builder()
            .provider(EmbeddingProvider.MOCK)
            .modelName(modelName)
            .modelVersion(MODEL_VERSION)
            .dimension(DIMENSION)
            .distanceMetric(DistanceMetric.COSINE)
            .isActive(isActive)
            .isSearchable(isSearchable)
            .vectorStorageStrategy(VectorStorageStrategy.SINGLE_DIMENSION)
            .build();
    }
}
