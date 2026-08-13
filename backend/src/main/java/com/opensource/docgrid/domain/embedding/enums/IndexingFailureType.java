package com.opensource.docgrid.domain.embedding.enums;

/**
 * Worker가 보고할 수 있는 인덱싱 실패 원인과 서버의 Retry 가능 정책을 정의한다.
 *
 * <p>외부 요청은 이 제한된 분류만 전달하며, Retry 여부를 직접 지정할 수 없다. 자유 형식 오류 메시지는
 * 진단 정보로만 저장되고 Job 상태 전이 결정에는 사용하지 않는다.
 */
public enum IndexingFailureType {

    STORAGE_UNAVAILABLE(true),
    DOCUMENT_CONTENT_INVALID(false),
    EMBEDDING_PROVIDER_UNAVAILABLE(true),
    EMBEDDING_RESULT_INVALID(false),
    INDEXING_STATE_INCONSISTENT(false),
    WORKER_INTERNAL_ERROR(true);

    private final boolean retryable;

    IndexingFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
