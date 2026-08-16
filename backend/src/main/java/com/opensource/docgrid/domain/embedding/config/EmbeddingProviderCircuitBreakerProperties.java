package com.opensource.docgrid.domain.embedding.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Embedding Provider Circuit Breaker의 연속 실패 임계치와 Open 유지 시간을 바인딩한다.
 *
 * <p>HTTP 실패 분류와 상태 전이는 Client 계층이 담당하며, 이 설정은 Backend JVM별 Circuit의
 * 활성화 여부와 안전한 양수 범위만 책임진다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "embedding.provider.circuit-breaker")
public class EmbeddingProviderCircuitBreakerProperties {

    private boolean enabled = true;

    @Min(1)
    private int failureThreshold = 3;

    @NotNull
    private Duration openDuration = Duration.ofSeconds(30);

    /**
     * Circuit이 즉시 반복 Probe하는 설정을 시작 단계에서 차단한다.
     */
    @AssertTrue(message = "Embedding Provider Circuit Open 시간은 0보다 커야 합니다.")
    public boolean isOpenDurationValid() {
        return openDuration != null
            && !openDuration.isZero()
            && !openDuration.isNegative();
    }
}
