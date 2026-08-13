package com.opensource.docgrid.domain.sync.enums;

/**
 * Outbox Event의 전달 생명주기를 표현한다.
 *
 * <p>PENDING과 PROCESSING은 Dispatcher의 Lease 소유권 경계이고, PROCESSED와 FAILED는 후속 처리가
 * 끝난 종결 상태다.
 */
public enum SyncEventStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
