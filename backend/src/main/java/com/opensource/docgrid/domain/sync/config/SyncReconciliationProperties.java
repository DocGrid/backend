package com.opensource.docgrid.domain.sync.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Reconciler의 활성화 여부, Batch 크기, 실행 주기와 정체 판단 임계시간을 바인딩한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "sync.reconciliation")
public class SyncReconciliationProperties {

    private boolean enabled = false;

    @NotNull
    private SyncReconciliationMode mode = SyncReconciliationMode.DRY_RUN;

    @Min(1)
    private int batchSize = 100;

    @NotNull
    private Duration interval = Duration.ofMinutes(5);

    @NotNull
    private Duration stalledThreshold = Duration.ofMinutes(15);

    @AssertTrue(message = "Reconciliation 실행 주기와 정체 임계시간은 0보다 커야 합니다.")
    public boolean isTimingValid() {
        return isPositive(interval) && isPositive(stalledThreshold);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
