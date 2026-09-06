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
     *
     * @param currentRetryCount 이미 수행한 재시도 횟수
     * @param minimumRetryDelay Provider가 요구한 최소 대기 시간, 요구가 없으면 {@link Duration#ZERO}
     * @return Jitter와 Provider 최소 대기 시간을 모두 만족하는 지연
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

    /**
     * 최초 지연을 Retry 횟수만큼 두 배로 늘리되 설정된 최대 지연을 넘기지 않는다.
     */
    private Duration calculateExponentialDelay(int currentRetryCount, Duration maxDelay) {
        // 1. 첫 실패의 재시도에는 설정된 최초 지연을 그대로 적용한다.
        Duration delay = workerProperties.getRetryInitialDelay();
        for (int retry = 0; retry < currentRetryCount; retry++) {
            // 2. 두 배가 상한에 닿는 순간 반환해 잘못된 큰 Retry 횟수에서도 Duration Overflow를 피한다.
            if (delay.compareTo(maxDelay.minus(delay)) >= 0) {
                return maxDelay;
            }

            // 3. 아직 상한 미만인 구간만 두 배로 늘린다.
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    /**
     * 동일 Retry 단계의 Job이 한 시점에 몰리지 않도록 설정 비율 범위의 무작위 변동을 적용한다.
     */
    private Duration applyJitter(Duration delay, Duration maxDelay) {
        // 1. Jitter가 비활성화된 환경에서는 입력 지연을 그대로 보존한다.
        double jitterRatio = workerProperties.getRetryJitterRatio();
        if (jitterRatio == 0.0) {
            return delay;
        }

        // 2. 상·하한을 모두 포함하는 비율을 선택해 기본 지연을 분산시킨다.
        double factor = ThreadLocalRandom.current().nextDouble(
            1.0 - jitterRatio,
            Math.nextUp(1.0 + jitterRatio)
        );

        // 3. 0ms 재시도와 설정 최대 지연 초과를 모두 방지한다.
        long jitteredMillis = Math.max(1L, Math.round(delay.toMillis() * factor));
        Duration jitteredDelay = Duration.ofMillis(jitteredMillis);
        return jitteredDelay.compareTo(maxDelay) > 0 ? maxDelay : jitteredDelay;
    }
}
