package com.opensource.docgrid.domain.permission.enums;

/**
 * user_document_access_cache에 저장된 접근 권한이 어디서 파생되었는지 나타내는 출처 타입.
 * OWNER: 문서 소유자, DIRECT_DOCUMENT_PERMISSION: document_permissions의 USER 대상 직접 권한,
 * DIRECT_COLLECTION_PERMISSION: collection_permissions의 USER 대상 직접 권한.
 * ROLE/DEPARTMENT 기반 권한은 이 캐시에 저장하지 않는다(live predicate로 판단).
 */
public enum AccessSourceType {
    OWNER,
    DIRECT_DOCUMENT_PERMISSION,
    DIRECT_COLLECTION_PERMISSION
}
