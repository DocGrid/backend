package com.opensource.docgrid.domain.embedding.enums;

/**
 * 개별 임베딩(embeddings) 레코드의 유효 상태.
 * ACTIVE: 검색 대상, STALE: 최신 버전이 아니어서 검색 제외 후보, DELETED: 삭제됨.
 */
public enum EmbeddingStatus {
    ACTIVE,
    STALE,
    DELETED
}
