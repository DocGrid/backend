package com.opensource.docgrid.domain.search.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/**
 * 벡터 검색 관련성 및 문서 다양성 설정의 기본값과 허용 범위를 검증한다.
 */
@DisplayName("VectorSearchProperties 검증 테스트")
class VectorSearchPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "0.30", "1.0"})
    @DisplayName("정상 케이스: 0부터 1까지의 최소 유사도를 허용한다")
    void validate_inRange_hasNoViolations(String value) {
        VectorSearchProperties properties = propertiesWith(value);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01"})
    @DisplayName("예외 케이스: 0부터 1 범위를 벗어난 최소 유사도를 거부한다")
    void validate_outOfRange_hasViolation(String value) {
        VectorSearchProperties properties = propertiesWith(value);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("예외 케이스: 최소 유사도가 null이면 거부한다")
    void validate_null_hasViolation() {
        VectorSearchProperties properties = new VectorSearchProperties();
        properties.setMinSimilarity(null);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("기본값은 0.30이다")
    void defaultValue_isPointThree() {
        VectorSearchProperties properties = new VectorSearchProperties();

        assertThat(properties.getMinSimilarity())
            .isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(properties.getMaxChunksPerDocument()).isEqualTo(2);
        assertThat(properties.getCandidatePoolMultiplier()).isEqualTo(4);
    }

    @Test
    @DisplayName("예외 케이스: 문서별 청크 상한과 후보 배수가 허용 범위를 벗어나면 거부한다")
    void validate_diversityPolicyOutOfRange_hasViolations() {
        VectorSearchProperties properties = new VectorSearchProperties();
        properties.setMaxChunksPerDocument(0);
        properties.setCandidatePoolMultiplier(11);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).hasSize(2);
        }
    }

    private VectorSearchProperties propertiesWith(String value) {
        VectorSearchProperties properties = new VectorSearchProperties();
        properties.setMinSimilarity(new BigDecimal(value));
        return properties;
    }
}
