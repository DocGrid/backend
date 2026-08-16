package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 인덱싱 Job의 지수 Backoff, Jitter와 Provider 최소 지연을 하나의 재예약 지연으로 계산한다.
 *
 * <p>Job 상태 변경이나 시각 저장은 수행하지 않는다. 설정 최대 지연은 애플리케이션 Backoff에만
 * 적용하며 외부 `Retry-After`는 Provider가 요청한 최소 재호출 시각으로 보존한다.
 */
@Component
@RequiredArgsConstructor
public class IndexingRetryDelayPolicy {

    private final IndexingWorkerProperties workerProperties;

    /**
     * 현재 Retry 횟수에 해당하는 다음 실행 지연을 반환한다.
     */
    public Duration calculate(int currentRetryCount, Duration minimumRetryDelay) {
        // 1. 잘못된 Retry 상태가 예약 시각 계산으로 전파되지 않도록 입력을 검증한다.
        if (currentRetryCount < 0
            || minimumRetryDelay == null
            || minimumRetryDelay.isNegative()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }

        // 2. Retry 횟수에 따른 지수 지연을 설정 상한 안에서 계산한다.
        Duration maxDelay = workerProperties.getRetryMaxDelay();
        Duration exponentialDelay = calculateExponentialDelay(currentRetryCount, maxDelay);
        // 3. 동일 시각 재시도 집중을 피하도록 Jitter를 적용하되 설정 상한을 유지한다.
        Duration jitteredDelay = applyJitter(exponentialDelay, maxDelay);
        // 4. Provider 최소 지연이 더 크면 상한보다 우선해 허용 시각 전 재호출을 막는다.
        return jitteredDelay.compareTo(minimumRetryDelay) >= 0
            ? jitteredDelay
            : minimumRetryDelay;
    }

    private Duration calculateExponentialDelay(int currentRetryCount, Duration maxDelay) {
        Duration delay = workerProperties.getRetryInitialDelay();
        for (int retry = 0; retry < currentRetryCount; retry++) {
            // 두 배가 상한에 닿는 순간 반환해 잘못된 큰 Retry 횟수에서도 Duration Overflow를 피한다.
            if (delay.compareTo(maxDelay.minus(delay)) >= 0) {
                return maxDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    private Duration applyJitter(Duration delay, Duration maxDelay) {
        double jitterRatio = workerProperties.getRetryJitterRatio();
        if (jitterRatio == 0.0) {
            return delay;
        }

        double factor = ThreadLocalRandom.current().nextDouble(
            1.0 - jitterRatio,
            Math.nextUp(1.0 + jitterRatio)
        );
        long jitteredMillis = Math.max(1L, Math.round(delay.toMillis() * factor));
        Duration jitteredDelay = Duration.ofMillis(jitteredMillis);
        return jitteredDelay.compareTo(maxDelay) > 0 ? maxDelay : jitteredDelay;
    }
}
