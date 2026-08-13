package com.opensource.docgrid.domain.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Worker 예외를 제한 실패 유형과 고정 메시지로 분류하고 소유권 상실 보고를 차단하는지 검증한다.
 */
@DisplayName("WorkerIndexingFailureClassifier 테스트")
class WorkerIndexingFailureClassifierTest {

    private final WorkerIndexingFailureClassifier classifier = new WorkerIndexingFailureClassifier();

    @Test
    @DisplayName("저장소와 Provider 장애는 Retry 가능한 외부 실패로 분류한다")
    void classify_mapsRetryableExternalFailures() {
        WorkerIndexingFailure storage = classifier.classify(
            new DocGridException(ErrorCode.FILE_STORAGE_FAILED, "sensitive object key")
        );
        WorkerIndexingFailure provider = classifier.classify(
            new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE, "sensitive endpoint")
        );

        assertThat(storage.failureType()).isEqualTo(IndexingFailureType.STORAGE_UNAVAILABLE);
        assertThat(storage.safeMessage()).doesNotContain("sensitive");
        assertThat(provider.failureType())
            .isEqualTo(IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE);
        assertThat(provider.safeMessage()).doesNotContain("sensitive");
    }

    @Test
    @DisplayName("문서·Vector·상태 오류를 영구 실패 유형으로 분류한다")
    void classify_mapsNonRetryableFailures() {
        assertThat(classifier.classify(new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY))
            .failureType()).isEqualTo(IndexingFailureType.DOCUMENT_CONTENT_INVALID);
        assertThat(classifier.classify(new DocGridException(ErrorCode.DOCUMENT_OCR_REQUIRED))
            .failureType()).isEqualTo(IndexingFailureType.DOCUMENT_CONTENT_INVALID);
        assertThat(classifier.classify(new DocGridException(ErrorCode.DOCUMENT_PDF_ENCRYPTED))
            .failureType()).isEqualTo(IndexingFailureType.DOCUMENT_CONTENT_INVALID);
        assertThat(classifier.classify(new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED))
            .failureType()).isEqualTo(IndexingFailureType.DOCUMENT_CONTENT_INVALID);
        assertThat(classifier.classify(new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID))
            .failureType()).isEqualTo(IndexingFailureType.EMBEDDING_RESULT_INVALID);
        assertThat(classifier.classify(new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT))
            .failureType()).isEqualTo(IndexingFailureType.INDEXING_STATE_INCONSISTENT);
    }

    @Test
    @DisplayName("소유권과 Lease 오류는 과거 Claim 실패 보고에서 제외한다")
    void classify_skipsOwnershipLostFailures() {
        WorkerIndexingFailure failure = classifier.classify(
            new DocGridException(ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED)
        );

        assertThat(failure.reportable()).isFalse();
        assertThat(failure.failureType()).isNull();
        assertThat(failure.diagnosticCode()).isEqualTo("EMBEDDING-JOB-004");
    }

    @Test
    @DisplayName("알 수 없는 Runtime 오류는 Worker 내부 Retry 유형으로 제한한다")
    void classify_mapsUnknownRuntimeFailure() {
        WorkerIndexingFailure failure = classifier.classify(
            new IllegalStateException("sensitive runtime detail")
        );

        assertThat(failure.failureType()).isEqualTo(IndexingFailureType.WORKER_INTERNAL_ERROR);
        assertThat(failure.safeMessage()).doesNotContain("sensitive");
    }
}
