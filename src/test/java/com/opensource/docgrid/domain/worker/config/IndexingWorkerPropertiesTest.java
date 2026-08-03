package com.opensource.docgrid.domain.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker Lease와 Retry 지연 설정의 기본값 및 애플리케이션 시작 단계 유효성 검사를 검증하는 단위 테스트.
 *
 * <p>정상적인 양수 기간은 허용하고 발급 즉시 만료되는 0 또는 음수 기간은 차단하는지 확인한다.
 */
@DisplayName("IndexingWorkerProperties 테스트")
class IndexingWorkerPropertiesTest {

    @Test
    @DisplayName("기본 Lease 기간은 5분이며 유효하다")
    void defaultLeaseDuration_isValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getLeaseDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.isLeaseDurationValid()).isTrue();
    }

    @Test
    @DisplayName("Lease 기간이 0 이하이면 유효하지 않다")
    void leaseDuration_isInvalid_when_notPositive() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setLeaseDuration(Duration.ZERO);
        assertThat(properties.isLeaseDurationValid()).isFalse();

        properties.setLeaseDuration(Duration.ofSeconds(-1));
        assertThat(properties.isLeaseDurationValid()).isFalse();
    }

    @Test
    @DisplayName("기본 Retry 지연은 10초부터 5분까지이며 유효하다")
    void defaultRetryDelay_isValid() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        assertThat(properties.getRetryInitialDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getRetryMaxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.isRetryDelayValid()).isTrue();
    }

    @Test
    @DisplayName("Retry 지연이 0 이하이거나 최대 지연이 더 짧으면 유효하지 않다")
    void retryDelay_isInvalid_when_nonPositiveOrReversed() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();

        properties.setRetryInitialDelay(Duration.ZERO);
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryInitialDelay(Duration.ofSeconds(-1));
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryInitialDelay(Duration.ofSeconds(10));
        properties.setRetryMaxDelay(Duration.ofSeconds(9));
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setRetryMaxDelay(Duration.ofSeconds(10));
        assertThat(properties.isRetryDelayValid()).isTrue();
    }
}
