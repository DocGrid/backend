package com.opensource.docgrid.domain.failover.enums;

/**
 * OpenSQL HA 장애 관련 이벤트 종류.
 * PRIMARY_DOWN: Primary 장애 감지, STANDBY_PROMOTED: Standby 승격,
 * RECONNECT_SUCCESS: 재연결 성공, FAILOVER_FAILED: Failover 실패.
 */
public enum FailoverEventType {
    PRIMARY_DOWN,
    STANDBY_PROMOTED,
    RECONNECT_SUCCESS,
    FAILOVER_FAILED
}
