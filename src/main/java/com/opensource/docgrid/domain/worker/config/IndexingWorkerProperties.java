package com.opensource.docgrid.domain.worker.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 인덱싱 Worker의 실행 여부, Heartbeat, DEAD 판정, Job Lease 시간을 바인딩하는 설정 클래스.
 *
 * <p>{@code indexing.worker} 환경 설정을 타입 안전한 {@link Duration}으로 제공하고, 애플리케이션 시작
 * 단계에서 서로 모순되거나 0 이하인 시간 설정을 차단한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "indexing.worker")
public class IndexingWorkerProperties {

    // API 전용 실행에서는 Worker 등록과 Scheduler가 동작하지 않도록 기본값을 false로 유지한다.
    private boolean enabled = false;

    @NotBlank
    private String name = "indexing-worker";

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    @NotNull
    private Duration deadThreshold = Duration.ofSeconds(30);

    // Claim 후 Worker가 소유권을 유지하는 기본 시간이다. 만료 복구는 후속 처리에서 사용한다.
    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5);

    /**
     * Heartbeat가 양수이고 DEAD 기준보다 짧은지 검증한다.
     */
    @AssertTrue(message = "Heartbeat 주기는 0보다 크고 DEAD 기준 시간보다 짧아야 합니다.")
    public boolean isTimingValid() {
        return heartbeatInterval != null
            && deadThreshold != null
            && !heartbeatInterval.isZero()
            && !heartbeatInterval.isNegative()
            && deadThreshold.compareTo(heartbeatInterval) > 0;
    }

    /**
     * 발급 즉시 만료되는 Lease가 만들어지지 않도록 Lease 기간이 양수인지 검증한다.
     */
    @AssertTrue(message = "Lease 기간은 0보다 커야 합니다.")
    public boolean isLeaseDurationValid() {
        return leaseDuration != null
            && !leaseDuration.isZero()
            && !leaseDuration.isNegative();
    }
}
