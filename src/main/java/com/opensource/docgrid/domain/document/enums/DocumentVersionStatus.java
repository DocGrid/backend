package com.opensource.docgrid.domain.document.enums;

/**
 * 문서 버전(document_versions)의 파이프라인 진행 상태.
 * UPLOADED -> PARSING -> CHUNKED -> EMBEDDING -> INDEXED, 실패 시 FAILED.
 */
public enum DocumentVersionStatus {
    UPLOADED,
    PARSING,
    CHUNKED,
    EMBEDDING,
    INDEXED,
    FAILED
}
