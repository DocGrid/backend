package com.opensource.docgrid.domain.sync.enums;

/**
 * 동기화 Event가 설명하는 도메인 Aggregate의 경계를 정의한다.
 *
 * <p>Dispatcher는 이 값과 aggregateId를 조합해 현재 도메인 상태를 다시 읽으며, Event Payload를
 * 최신 상태의 원장으로 사용하지 않는다.
 */
public enum SyncAggregateType {
    DOCUMENT_VERSION,
    DOCUMENT,
    PERMISSION,
    EMBEDDING_MODEL
}
