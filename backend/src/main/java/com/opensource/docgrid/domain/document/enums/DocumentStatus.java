package com.opensource.docgrid.domain.document.enums;

/**
 * 문서(논리적 루트) 전체 상태.
 * DRAFT: 초안, UPLOADED: 업로드 완료, INDEXING: 색인 중, INDEXED: 색인 완료,
 * FAILED: 실패, ARCHIVED: 보관, DELETED: 삭제.
 */
public enum DocumentStatus {
    DRAFT,
    UPLOADED,
    INDEXING,
    INDEXED,
    FAILED,
    ARCHIVED,
    DELETED
}
