package com.opensource.docgrid.domain.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
