package com.opensource.docgrid.domain.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Embedding Provider Circuit Breaker 기본 임계치와 Open 시간의 시작 단계 검증만 확인한다.
 *
 * <p>Circuit 상태 전이와 HTTP 호출은 이 설정 단위 테스트의 경계에 포함하지 않는다.
 */
@DisplayName("EmbeddingProviderCircuitBreakerProperties 테스트")
class EmbeddingProviderCircuitBreakerPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("기본 Circuit은 연속 실패 3회 뒤 30초 동안 열린다")
    void defaults_areValid() {
        EmbeddingProviderCircuitBreakerProperties properties =
            new EmbeddingProviderCircuitBreakerProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getFailureThreshold()).isEqualTo(3);
        assertThat(properties.getOpenDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("실패 임계치는 1 이상이고 Open 시간은 양수여야 한다")
    void settings_areInvalid_when_nonPositive() {
        EmbeddingProviderCircuitBreakerProperties properties =
            new EmbeddingProviderCircuitBreakerProperties();

        properties.setFailureThreshold(0);
        properties.setOpenDuration(Duration.ZERO);

        assertThat(validator.validate(properties)).hasSize(2);
    }
}
