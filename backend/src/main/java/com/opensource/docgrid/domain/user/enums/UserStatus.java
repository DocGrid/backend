package com.opensource.docgrid.domain.user.enums;

/**
 * 사용자 계정 상태.
 * ACTIVE: 정상 사용 가능, INACTIVE: 비활성화, LOCKED: 잠금, DELETED: 삭제됨.
 */
public enum UserStatus {
    ACTIVE,
    INACTIVE,
    LOCKED,
    DELETED
}
