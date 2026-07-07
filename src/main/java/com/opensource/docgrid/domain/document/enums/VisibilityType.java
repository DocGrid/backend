package com.opensource.docgrid.domain.document.enums;

/**
 * 문서/컬렉션의 공개 범위.
 * PRIVATE: 소유자만, COLLECTION: 소속 컬렉션 권한자만, DEPARTMENT: 부서 전체, PUBLIC: 전체 공개.
 */
public enum VisibilityType {
    PRIVATE,
    COLLECTION,
    DEPARTMENT,
    PUBLIC
}
