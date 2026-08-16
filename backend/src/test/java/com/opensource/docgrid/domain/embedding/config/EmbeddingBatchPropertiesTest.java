package com.opensource.docgrid.domain.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 문서 Embedding Batch 크기의 기본값과 서버 계약 범위 검증을 확인한다.
 */
@DisplayName("EmbeddingBatchProperties 테스트")
class EmbeddingBatchPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("실제 PDF BGE-M3 Benchmark로 선택한 기본 Batch 크기 4는 유효하다")
    void defaultBatchSizeIsValid() {
        EmbeddingBatchProperties properties = new EmbeddingBatchProperties();

        assertThat(properties.getBatchSize()).isEqualTo(4);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("Batch 크기는 1 이상 64 이하만 허용한다")
    void batchSizeMustStayWithinServerContract() {
        EmbeddingBatchProperties properties = new EmbeddingBatchProperties();

        properties.setBatchSize(0);
        assertThat(validator.validate(properties)).hasSize(1);

        properties.setBatchSize(65);
        assertThat(validator.validate(properties)).hasSize(1);
    }
}
