package com.opensource.docgrid.domain.embedding.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Backend JVM에서 공유하는 Embedding Provider Circuit 상태와 단일 Half-open Probe를 관리한다.
 *
 * <p>HTTP 실행 전 Permission만 발급하고 실제 요청·응답 계약에는 관여하지 않는다. 모든 상태 변경은
 * synchronized 경계 안에서 직렬화하며, Open 전 이미 시작된 요청의 늦은 결과가 새 상태를 덮어쓰지
 * 못하도록 세대 번호를 함께 검증한다.
 */
@Slf4j
@Component
public class EmbeddingProviderCircuitBreaker {

    private static final Duration HALF_OPEN_RETRY_DELAY = Duration.ofSeconds(1);

    private final EmbeddingProviderCircuitBreakerProperties properties;
    private final Clock clock;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private Instant openUntil;
    private long generation;
    private boolean halfOpenProbeInProgress;

    public EmbeddingProviderCircuitBreaker(
        EmbeddingProviderCircuitBreakerProperties properties,
        Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 현재 상태에서 실제 Provider 호출 Permission을 발급하거나 Open 오류로 빠르게 거절한다.
     */
    public synchronized CallPermission acquirePermission() {
        if (!properties.isEnabled()) {
            return new CallPermission(generation, false, true);
        }

        Instant now = clock.instant();
        if (state == CircuitState.OPEN) {
            if (now.isBefore(openUntil)) {
                throw circuitOpen(Duration.between(now, openUntil));
            }
            state = CircuitState.HALF_OPEN;
            halfOpenProbeInProgress = false;
        }
        if (state == CircuitState.HALF_OPEN) {
            if (halfOpenProbeInProgress) {
                throw circuitOpen(HALF_OPEN_RETRY_DELAY);
            }
            halfOpenProbeInProgress = true;
            return new CallPermission(generation, true, false);
        }
        return new CallPermission(generation, false, false);
    }

    /**
     * Provider 도달 성공을 기록하고 현재 세대의 Half-open Probe라면 Circuit을 닫는다.
     */
    public synchronized void recordSuccess(CallPermission permission) {
        if (permission.disabled() || permission.generation() != generation) {
            return;
        }
        if (permission.halfOpenProbe() && state == CircuitState.HALF_OPEN) {
            closeCircuit();
            return;
        }
        if (state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    /**
     * Retryable Provider 실패만 연속 실패로 집계하고 임계치 또는 Probe 실패 시 Circuit을 연다.
     */
    public synchronized Duration recordFailure(
        CallPermission permission,
        boolean circuitFailure
    ) {
        if (permission.disabled()) {
            return Duration.ZERO;
        }
        if (permission.generation() != generation) {
            return remainingOpenDelay();
        }
        if (!circuitFailure) {
            recordSuccess(permission);
            return Duration.ZERO;
        }
        if (permission.halfOpenProbe() && state == CircuitState.HALF_OPEN) {
            return openCircuit();
        }
        if (state != CircuitState.CLOSED) {
            return remainingOpenDelay();
        }

        consecutiveFailures++;
        if (consecutiveFailures >= properties.getFailureThreshold()) {
            return openCircuit();
        }
        return Duration.ZERO;
    }

    private Duration openCircuit() {
        state = CircuitState.OPEN;
        openUntil = clock.instant().plus(properties.getOpenDuration());
        consecutiveFailures = 0;
        halfOpenProbeInProgress = false;
        generation++;
        log.warn(
            "Embedding Provider Circuit을 열었습니다. openDurationMs={}",
            properties.getOpenDuration().toMillis()
        );
        return properties.getOpenDuration();
    }

    private void closeCircuit() {
        state = CircuitState.CLOSED;
        openUntil = null;
        consecutiveFailures = 0;
        halfOpenProbeInProgress = false;
        generation++;
        log.info("Embedding Provider Circuit이 정상 호출로 닫혔습니다.");
    }

    private EmbeddingProviderException circuitOpen(Duration retryDelay) {
        return new EmbeddingProviderException(
            ErrorCode.EMBEDDING_PROVIDER_CIRCUIT_OPEN,
            retryDelay,
            false
        );
    }

    private Duration remainingOpenDelay() {
        if (state == CircuitState.OPEN && openUntil != null) {
            Duration remaining = Duration.between(clock.instant(), openUntil);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
        return state == CircuitState.HALF_OPEN ? HALF_OPEN_RETRY_DELAY : Duration.ZERO;
    }

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * 호출 시작 세대와 Half-open Probe 소유권을 완료 Callback까지 전달하는 불변 Permission이다.
     */
    public record CallPermission(long generation, boolean halfOpenProbe, boolean disabled) {
    }
}
