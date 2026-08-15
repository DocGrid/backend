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
 * 벡터 검색 최소 유사도 설정이 코사인 유사도의 유효 범위만 허용하는지 검증한다.
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
        assertThat(new VectorSearchProperties().getMinSimilarity())
            .isEqualByComparingTo(new BigDecimal("0.30"));
    }

    private VectorSearchProperties propertiesWith(String value) {
        VectorSearchProperties properties = new VectorSearchProperties();
        properties.setMinSimilarity(new BigDecimal(value));
        return properties;
    }
}
