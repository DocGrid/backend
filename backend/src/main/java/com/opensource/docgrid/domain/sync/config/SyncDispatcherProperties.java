package com.opensource.docgrid.domain.sync.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Sync Dispatcher의 실행 여부, Polling, Lease, 복구 Batch와 Retry Backoff 설정을 바인딩한다.
 *
 * <p>API 전용 인스턴스에서는 enabled를 false로 유지할 수 있고, 다중 Dispatcher는 서로 다른 name을
 * 사용해 Event의 현재 소유 인스턴스를 운영 화면에서 식별한다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "sync.dispatcher")
public class SyncDispatcherProperties {

    private boolean enabled = false;

    @NotBlank
    private String name = "sync-dispatcher";

    @NotNull
    private Duration pollingInterval = Duration.ofSeconds(1);

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(30);

    @NotNull
    private Duration leaseRecoveryInterval = Duration.ofSeconds(10);

    @Min(1)
    private int leaseRecoveryBatchSize = 100;

    @NotNull
    private Duration retryInitialDelay = Duration.ofSeconds(5);

    @NotNull
    private Duration retryMaxDelay = Duration.ofMinutes(1);

    @AssertTrue(message = "Sync Dispatcher의 Polling과 Lease 시간은 0보다 커야 합니다.")
    public boolean isTimingValid() {
        return isPositive(pollingInterval)
            && isPositive(leaseDuration)
            && isPositive(leaseRecoveryInterval);
    }

    @AssertTrue(message = "Sync Retry 최대 지연은 양수인 초기 지연보다 짧을 수 없습니다.")
    public boolean isRetryDelayValid() {
        return isPositive(retryInitialDelay)
            && retryMaxDelay != null
            && retryMaxDelay.compareTo(retryInitialDelay) >= 0;
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
