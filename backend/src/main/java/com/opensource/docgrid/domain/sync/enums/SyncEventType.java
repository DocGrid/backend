package com.opensource.docgrid.domain.sync.enums;

/**
 * Outbox에서 전달할 후속 동기화 작업의 종류를 정의한다.
 *
 * <p>각 값은 Dispatcher Handler 하나의 멱등한 책임에 대응하며, 인덱싱 운영 이력인
 * {@code indexing_events}와 분리된다.
 */
public enum SyncEventType {
    DOCUMENT_VERSION_CREATED,
    DOCUMENT_REINDEX_REQUESTED,
    DOCUMENT_DELETED,
    PERMISSION_CACHE_REFRESH_REQUESTED,
    EMBEDDING_MODEL_ACTIVATED
}
