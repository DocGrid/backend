package com.opensource.docgrid.domain.embedding.client;

import java.time.Duration;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.Getter;

/**
 * Embedding Provider 실패의 공개 ErrorCode와 안전한 최소 재시도 지연을 함께 전달한다.
 *
 * <p>외부 응답 본문이나 Endpoint는 보존하지 않는다. Retry 지연은 Worker Job 예약에만 사용하며 실제
 * HTTP 호출 내부 재시도를 수행하지 않는다.
 */
@Getter
public class EmbeddingProviderException extends DocGridException {

    private final Duration minimumRetryDelay;
    private final boolean circuitFailure;

    public EmbeddingProviderException(
        ErrorCode errorCode,
        Duration minimumRetryDelay,
        boolean circuitFailure,
        Throwable cause
    ) {
        super(errorCode, cause);
        this.minimumRetryDelay = normalize(minimumRetryDelay);
        this.circuitFailure = circuitFailure;
    }

    public EmbeddingProviderException(
        ErrorCode errorCode,
        Duration minimumRetryDelay,
        boolean circuitFailure
    ) {
        this(errorCode, minimumRetryDelay, circuitFailure, null);
    }

    /**
     * 기존 Provider 지연과 Circuit Open 잔여 시간 중 더 긴 값을 보존한 새 예외를 반환한다.
     */
    public EmbeddingProviderException withMinimumRetryDelay(Duration candidateDelay) {
        Duration normalizedCandidate = normalize(candidateDelay);
        if (minimumRetryDelay.compareTo(normalizedCandidate) >= 0) {
            return this;
        }
        return new EmbeddingProviderException(
            getErrorCode(),
            normalizedCandidate,
            circuitFailure,
            getCause()
        );
    }

    private static Duration normalize(Duration minimumRetryDelay) {
        if (minimumRetryDelay == null || minimumRetryDelay.isNegative()) {
            return Duration.ZERO;
        }
        return minimumRetryDelay;
    }
}
