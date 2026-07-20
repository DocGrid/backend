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

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "indexing.worker")
public class IndexingWorkerProperties {

    private boolean enabled = false;

    @NotBlank
    private String name = "indexing-worker";

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    @NotNull
    private Duration deadThreshold = Duration.ofSeconds(30);

    @AssertTrue(message = "Heartbeat 주기는 0보다 크고 DEAD 기준 시간보다 짧아야 합니다.")
    public boolean isTimingValid() {
        return heartbeatInterval != null
            && deadThreshold != null
            && !heartbeatInterval.isZero()
            && !heartbeatInterval.isNegative()
            && deadThreshold.compareTo(heartbeatInterval) > 0;
    }
}
