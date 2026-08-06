package com.opensource.docgrid.domain.worker.enums;

/**
 * 인덱싱 파이프라인 상태 변경 이벤트 종류(indexing_events).
 */
public enum IndexingEventType {
    JOB_CREATED,
    LOCKED,
    PARSE_STARTED,
    PARSE_FAILED,
    CHUNKED,
    EMBEDDING_STARTED,
    EMBEDDING_FAILED,
    INDEXED,
    LEASE_EXPIRED,
    FAILED,
    RETRY,
    MANUAL_RETRY
}
