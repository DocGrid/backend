package com.opensource.docgrid.domain.embedding.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.enums.VectorStorageStrategy;

@DisplayName("EmbeddingModel 생성 검증 테스트")
class EmbeddingModelTest {

    @Test
    @DisplayName("모델 이름이 공백이면 생성할 수 없다")
    void create_throws_when_modelNameIsBlank() {
        assertThatThrownBy(() -> createModel(" ", "v1", 1024))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("모델 버전이 공백이면 생성할 수 없다")
    void create_throws_when_modelVersionIsBlank() {
        assertThatThrownBy(() -> createModel("mock-bge-m3", " ", 1024))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("차원이 0이면 생성할 수 없다")
    void create_throws_when_dimensionIsZero() {
        assertThatThrownBy(() -> createModel("mock-bge-m3", "v1", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("차원이 음수이면 생성할 수 없다")
    void create_throws_when_dimensionIsNegative() {
        assertThatThrownBy(() -> createModel("mock-bge-m3", "v1", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private EmbeddingModel createModel(String modelName, String modelVersion, int dimension) {
        return EmbeddingModel.builder()
            .provider(EmbeddingProvider.MOCK)
            .modelName(modelName)
            .modelVersion(modelVersion)
            .dimension(dimension)
            .distanceMetric(DistanceMetric.COSINE)
            .isActive(true)
            .isSearchable(true)
            .vectorStorageStrategy(VectorStorageStrategy.SINGLE_DIMENSION)
            .build();
    }
}
