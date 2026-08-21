package com.opensource.docgrid.domain.embedding.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 인덱싱 실패 유형별 Retry 가능 정책이 외부 입력과 무관하게 고정되는지 검증한다.
 */
@DisplayName("IndexingFailureType 테스트")
class IndexingFailureTypeTest {

    @ParameterizedTest
    @EnumSource(value = IndexingFailureType.class, names = {
        "STORAGE_UNAVAILABLE", "EMBEDDING_PROVIDER_UNAVAILABLE",
        "EMBEDDING_PROVIDER_OVERLOADED", "EMBEDDING_PROVIDER_TIMEOUT",
        "EMBEDDING_PROVIDER_CIRCUIT_OPEN", "WORKER_INTERNAL_ERROR"
    })
    @DisplayName("일시적인 인프라와 Worker 내부 오류는 Retry할 수 있다")
    void retryableTypes_returnTrue(IndexingFailureType failureType) {
        assertThat(failureType.isRetryable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = IndexingFailureType.class, names = {
        "STORAGE_CONFIGURATION_INVALID", "STORAGE_OBJECT_MISSING", "DOCUMENT_CONTENT_INVALID",
        "EMBEDDING_REQUEST_INVALID", "EMBEDDING_RESULT_INVALID", "INDEXING_STATE_INCONSISTENT"
    })
    @DisplayName("데이터와 상태 불변식 오류는 Retry하지 않는다")
    void permanentTypes_returnFalse(IndexingFailureType failureType) {
        assertThat(failureType.isRetryable()).isFalse();
    }
}
