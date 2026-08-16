package com.opensource.docgrid.domain.embedding.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.opensource.docgrid.domain.embedding.dto.request.EmbedBatchRequest;
import com.opensource.docgrid.domain.embedding.dto.request.EmbedRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 Embedding Server의 단건·Batch Vector 생성 HTTP 계약을 담당한다.
 *
 * <p>검색 단건과 문서 Batch는 같은 Provider를 사용하지만 응답 시간 예산이 다르므로 전용 HTTP Client를
 * 분리한다. 모델 선택과 Vector 차원 검증은 호출 Service가 담당하며, 이 클래스는 요청 계약과 전송 오류
 * 변환 경계만 책임진다. 공유 Circuit은 연속 가용성 실패를 빠르게 차단하되 HTTP 호출 내부 재시도는
 * 수행하지 않는다.
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RestClient queryRestClient;
    private final RestClient documentRestClient;
    private final EmbeddingProviderCircuitBreaker circuitBreaker;

    public EmbeddingClient(
        @Qualifier("embeddingRestClient") RestClient queryRestClient,
        @Qualifier("documentEmbeddingRestClient") RestClient documentRestClient,
        EmbeddingProviderCircuitBreaker circuitBreaker
    ) {
        this.queryRestClient = queryRestClient;
        this.documentRestClient = documentRestClient;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 입력 Text를 외부 서버에 전달하고 Dense Vector를 반환한다.
     */
    public float[] embed(String text) {
        EmbeddingProviderCircuitBreaker.CallPermission permission =
            circuitBreaker.acquirePermission();
        EmbedServerResponse response;
        boolean resultRecorded = false;
        try {
            response = queryRestClient.post()
                .uri("/embed")
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedServerResponse.class);
            circuitBreaker.recordSuccess(permission);
            resultRecorded = true;
        } catch (RestClientException exception) {
            EmbeddingProviderException providerException = translateFailure("단건", exception);
            Duration circuitDelay = circuitBreaker.recordFailure(
                permission,
                providerException.isCircuitFailure()
            );
            resultRecorded = true;
            throw providerException.withMinimumRetryDelay(circuitDelay);
        } finally {
            // 예상 밖 예외가 결과 기록을 건너뛰어도 Half-open Probe 소유권은 반드시 반환한다.
            if (!resultRecorded) {
                circuitBreaker.releasePermission(permission);
            }
        }

        return response == null ? null : response.vector();
    }

    /**
     * 정렬된 Text 목록을 Batch API로 전달하고 모델·개수·순서 계약을 검증한다.
     */
    public EmbedBatchServerResponse embedBatch(List<String> texts, int batchSize) {
        EmbeddingProviderCircuitBreaker.CallPermission permission =
            circuitBreaker.acquirePermission();
        EmbedBatchServerResponse response;
        boolean resultRecorded = false;
        try {
            response = documentRestClient.post()
                .uri("/embed/batch")
                .body(new EmbedBatchRequest(texts, batchSize))
                .retrieve()
                .body(EmbedBatchServerResponse.class);
            circuitBreaker.recordSuccess(permission);
            resultRecorded = true;
        } catch (RestClientException exception) {
            EmbeddingProviderException providerException = translateFailure("Batch", exception);
            Duration circuitDelay = circuitBreaker.recordFailure(
                permission,
                providerException.isCircuitFailure()
            );
            resultRecorded = true;
            throw providerException.withMinimumRetryDelay(circuitDelay);
        } finally {
            // 예상 밖 예외가 결과 기록을 건너뛰어도 Half-open Probe 소유권은 반드시 반환한다.
            if (!resultRecorded) {
                circuitBreaker.releasePermission(permission);
            }
        }

        validateBatchResponse(response, texts == null ? -1 : texts.size());
        return response;
    }

    private EmbeddingProviderException translateFailure(
        String operation,
        RestClientException exception
    ) {
        // 1. timeout, 과부하, 서버 장애와 영구 4xx를 분리해 Job Retry와 Circuit의 입력을 고정한다.
        ErrorCode errorCode = resolveErrorCode(exception);
        Duration minimumRetryDelay = errorCode == ErrorCode.EMBEDDING_PROVIDER_OVERLOADED
            ? retryAfter(exception)
            : Duration.ZERO;
        boolean circuitFailure = errorCode == ErrorCode.EMBEDDING_PROVIDER_TIMEOUT
            || errorCode == ErrorCode.EMBEDDING_PROVIDER_OVERLOADED
            || errorCode == ErrorCode.EMBEDDING_SERVER_UNAVAILABLE;

        // 2. 외부 응답 본문은 문서 내용이나 내부 정보를 포함할 수 있어 오류 유형만 기록한다.
        log.error(
            "임베딩 서버 {} 호출에 실패했습니다. errorCode={}, cause={}",
            operation,
            errorCode.getCode(),
            exception.getClass().getSimpleName()
        );
        return new EmbeddingProviderException(
            errorCode,
            minimumRetryDelay,
            circuitFailure,
            exception
        );
    }

    private ErrorCode resolveErrorCode(RestClientException exception) {
        if (isTimeout(exception)) {
            return ErrorCode.EMBEDDING_PROVIDER_TIMEOUT;
        }
        if (exception instanceof RestClientResponseException responseException) {
            if (responseException.getStatusCode().value() == 408) {
                return ErrorCode.EMBEDDING_PROVIDER_TIMEOUT;
            }
            if (responseException.getStatusCode().value() == 429) {
                return ErrorCode.EMBEDDING_PROVIDER_OVERLOADED;
            }
            if (responseException.getStatusCode().is4xxClientError()) {
                return ErrorCode.EMBEDDING_REQUEST_REJECTED;
            }
        }
        return ErrorCode.EMBEDDING_SERVER_UNAVAILABLE;
    }

    private boolean isTimeout(RestClientException exception) {
        if (!(exception instanceof ResourceAccessException)) {
            return false;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                || current instanceof SocketTimeoutException
                || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Duration retryAfter(RestClientException exception) {
        if (!(exception instanceof RestClientResponseException responseException)) {
            return Duration.ZERO;
        }
        HttpHeaders headers = responseException.getResponseHeaders();
        String rawValue = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(rawValue)) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(rawValue.trim());
            return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException exceptionCause) {
            return Duration.ZERO;
        }
    }

    private void validateBatchResponse(EmbedBatchServerResponse response, int expectedCount) {
        if (response == null
            || !StringUtils.hasText(response.model())
            || response.embeddings() == null
            || response.embeddings().size() != expectedCount) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        for (int index = 0; index < response.embeddings().size(); index++) {
            EmbedBatchItemResponse item = response.embeddings().get(index);
            if (item == null || item.index() != index) {
                throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
            }
        }
    }
}
