package com.opensource.docgrid.domain.worker.service;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderException;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Worker Pipeline 예외를 공개된 인덱싱 실패 정책과 안전한 고정 메시지로 분류한다.
 *
 * <p>예외 원문과 Stack Trace는 문서 내용이나 외부 연결 정보를 포함할 수 있으므로 DB 실패 메시지로
 * 전달하지 않는다. 저장소 설정·Object 누락은 영구 실패로 분리하고, 소유권 상실 오류는 과거 Claim으로
 * 상태를 덮어쓰지 않도록 보고 대상에서 제외한다.
 */
@Component
public class WorkerIndexingFailureClassifier {

    private static final Set<ErrorCode> OWNERSHIP_LOST_ERRORS = EnumSet.of(
        ErrorCode.EMBEDDING_JOB_NOT_FOUND,
        ErrorCode.EMBEDDING_JOB_NOT_PROCESSING,
        ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID,
        ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED,
        ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID,
        ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT
    );
    private static final Set<ErrorCode> DOCUMENT_CONTENT_ERRORS = EnumSet.of(
        ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
        ErrorCode.DOCUMENT_CONTENT_EMPTY,
        ErrorCode.DOCUMENT_TEXT_DECODING_FAILED,
        ErrorCode.DOCUMENT_PDF_ENCRYPTED,
        ErrorCode.DOCUMENT_OCR_REQUIRED,
        ErrorCode.DOCUMENT_PARSING_FAILED,
        ErrorCode.DOCUMENT_CONTENT_GARBLED
    );
    private static final Set<ErrorCode> EMBEDDING_RESULT_ERRORS = EnumSet.of(
        ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
        ErrorCode.EMBEDDING_VECTOR_INVALID
    );
    private static final Set<ErrorCode> INDEXING_STATE_ERRORS = EnumSet.of(
        ErrorCode.DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED,
        ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED,
        ErrorCode.INDEXING_STATUS_INCONSISTENT,
        ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING,
        ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT,
        ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT,
        ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED,
        ErrorCode.DOCUMENT_INDEXING_STALE_COMPLETION,
        ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT,
        ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT,
        ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT,
        ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED,
        ErrorCode.MULTIPLE_ACTIVE_EMBEDDING_MODELS
    );

    /**
     * 실행 예외를 실패 보고 가능 여부, 제한 유형과 고정 진단 메시지로 변환한다.
     */
    public WorkerIndexingFailure classify(RuntimeException exception) {
        // 1. 도메인 오류가 아닌 예상 밖 Runtime 예외는 내부 오류로 제한해 보고한다.
        if (!(exception instanceof DocGridException docGridException)) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.WORKER_INTERNAL_ERROR,
                "UNEXPECTED_RUNTIME_EXCEPTION",
                "Worker 내부 실행 오류로 인덱싱을 완료하지 못했습니다."
            );
        }

        // 2. 과거 Claim이 상태를 덮어쓰면 안 되는 소유권 오류는 실패 보고 대상에서 제외한다.
        ErrorCode errorCode = docGridException.getErrorCode();
        if (OWNERSHIP_LOST_ERRORS.contains(errorCode)) {
            return WorkerIndexingFailure.ownershipLost(errorCode.getCode());
        }

        // 3. 저장소·문서 내용·Provider·Vector 계약 오류를 재시도 정책별 공개 실패 유형으로 변환한다.
        if (errorCode == ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.STORAGE_CONFIGURATION_INVALID,
                errorCode.getCode(),
                "Worker 파일 저장소 설정이 원본 저장 위치와 일치하지 않습니다."
            );
        }
        if (errorCode == ErrorCode.FILE_OBJECT_NOT_FOUND) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.STORAGE_OBJECT_MISSING,
                errorCode.getCode(),
                "파일 Metadata가 가리키는 원본 Object를 찾을 수 없습니다."
            );
        }
        if (errorCode == ErrorCode.FILE_STORAGE_FAILED) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.STORAGE_UNAVAILABLE,
                errorCode.getCode(),
                "파일 저장소를 사용할 수 없어 인덱싱을 완료하지 못했습니다."
            );
        }
        if (DOCUMENT_CONTENT_ERRORS.contains(errorCode)) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.DOCUMENT_CONTENT_INVALID,
                errorCode.getCode(),
                "문서 내용을 인덱싱 가능한 텍스트로 처리할 수 없습니다."
            );
        }
        if (errorCode == ErrorCode.EMBEDDING_SERVER_UNAVAILABLE) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE,
                errorCode.getCode(),
                "Embedding Provider를 사용할 수 없어 인덱싱을 완료하지 못했습니다.",
                minimumRetryDelay(exception)
            );
        }
        // HTTP 429 admission 거절은 일시적이므로 Provider 비가용과 구분한 retryable 정책을 유지한다.
        if (errorCode == ErrorCode.EMBEDDING_PROVIDER_OVERLOADED) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED,
                errorCode.getCode(),
                "Embedding Provider 처리 용량을 초과해 인덱싱을 완료하지 못했습니다.",
                minimumRetryDelay(exception)
            );
        }
        if (errorCode == ErrorCode.EMBEDDING_PROVIDER_TIMEOUT) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_PROVIDER_TIMEOUT,
                errorCode.getCode(),
                "Embedding Provider 응답 제한 시간을 초과했습니다.",
                minimumRetryDelay(exception)
            );
        }
        if (errorCode == ErrorCode.EMBEDDING_PROVIDER_CIRCUIT_OPEN) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_PROVIDER_CIRCUIT_OPEN,
                errorCode.getCode(),
                "Embedding Provider 장애 보호로 호출을 중단했습니다.",
                minimumRetryDelay(exception)
            );
        }
        if (errorCode == ErrorCode.EMBEDDING_REQUEST_REJECTED) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_REQUEST_INVALID,
                errorCode.getCode(),
                "Embedding Provider 요청 계약이 유효하지 않습니다."
            );
        }
        if (EMBEDDING_RESULT_ERRORS.contains(errorCode)) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.EMBEDDING_RESULT_INVALID,
                errorCode.getCode(),
                "Embedding Provider 결과가 현재 모델 계약과 일치하지 않습니다."
            );
        }
        if (INDEXING_STATE_ERRORS.contains(errorCode)) {
            return WorkerIndexingFailure.reportable(
                IndexingFailureType.INDEXING_STATE_INCONSISTENT,
                errorCode.getCode(),
                "인덱싱 Job과 문서 파이프라인 상태가 일치하지 않습니다."
            );
        }

        // 4. 명시적으로 분류되지 않은 도메인 오류는 세부 정보를 숨긴 Worker 내부 오류로 처리한다.
        return WorkerIndexingFailure.reportable(
            IndexingFailureType.WORKER_INTERNAL_ERROR,
            errorCode.getCode(),
            "Worker 내부 실행 오류로 인덱싱을 완료하지 못했습니다."
        );
    }

    /**
     * Provider 예외에 포함된 Retry-After·Circuit 지연을 실패 보고 계약으로 전달한다.
     */
    private Duration minimumRetryDelay(RuntimeException exception) {
        if (exception instanceof EmbeddingProviderException providerException) {
            return providerException.getMinimumRetryDelay();
        }
        return Duration.ZERO;
    }
}
