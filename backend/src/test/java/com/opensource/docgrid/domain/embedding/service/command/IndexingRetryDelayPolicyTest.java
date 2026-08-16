package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Job Retry 지연의 지수 증가, Jitter 범위, Backoff 상한과 Provider 최소 지연 선택만 검증한다.
 *
 * <p>DB `next_retry_at` 저장과 Worker Claim은 이 순수 정책 단위 테스트의 경계에 포함하지 않는다.
 */
@DisplayName("IndexingRetryDelayPolicy 테스트")
class IndexingRetryDelayPolicyTest {

    private IndexingWorkerProperties properties;
    private IndexingRetryDelayPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new IndexingWorkerProperties();
        policy = new IndexingRetryDelayPolicy(properties);
    }

    @Test
    @DisplayName("첫 Retry 10초에 ±20% Jitter를 적용한다")
    void calculate_appliesConfiguredJitterRange() {
        for (int attempt = 0; attempt < 100; attempt++) {
            Duration delay = policy.calculate(0, Duration.ZERO);

            assertThat(delay).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12));
        }
    }

    @Test
    @DisplayName("Jitter가 0이면 Retry 횟수에 따라 10·20·40초로 지수 증가한다")
    void calculate_appliesExponentialBackoff() {
        properties.setRetryJitterRatio(0.0);

        assertThat(policy.calculate(0, Duration.ZERO)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.calculate(1, Duration.ZERO)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.calculate(2, Duration.ZERO)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("Retry-After가 Jitter 결과보다 길면 최소 지연으로 우선한다")
    void calculate_prefersProviderMinimumDelay() {
        Duration delay = policy.calculate(0, Duration.ofSeconds(15));

        assertThat(delay).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("지수·Jitter는 설정 상한을 지키고 더 긴 Provider 최소 지연은 보존한다")
    void calculate_capsBackoffAndPreservesProviderMinimum() {
        properties.setRetryMaxDelay(Duration.ofSeconds(40));

        assertThat(policy.calculate(10, Duration.ZERO)).isBetween(
            Duration.ofSeconds(32),
            Duration.ofSeconds(40)
        );
        assertThat(policy.calculate(0, Duration.ofMinutes(10)))
            .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("음수 Retry 횟수나 최소 지연은 상태 불일치로 거부한다")
    void calculate_rejectsInvalidInputs() {
        assertThatThrownBy(() -> policy.calculate(-1, Duration.ZERO))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
        assertThatThrownBy(() -> policy.calculate(0, Duration.ofSeconds(-1)))
            .isInstanceOf(DocGridException.class);
    }
}
