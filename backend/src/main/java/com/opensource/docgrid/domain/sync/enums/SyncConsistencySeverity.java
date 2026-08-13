package com.opensource.docgrid.domain.sync.enums;

/**
 * 동기화 불일치가 검색 가용성과 데이터 신뢰도에 미치는 영향을 구분한다.
 */
public enum SyncConsistencySeverity {
    WARNING,
    ERROR,
    CRITICAL
}
