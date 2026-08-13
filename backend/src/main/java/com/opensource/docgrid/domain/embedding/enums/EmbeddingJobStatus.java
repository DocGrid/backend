package com.opensource.docgrid.domain.embedding.enums;

/**
 * 임베딩 작업 큐(embedding_jobs)의 상태.
 * PENDING: 대기, PROCESSING: 처리 중, INDEXED: 완료, FAILED: 실패, CANCELED: 취소.
 */
public enum EmbeddingJobStatus {
    PENDING,
    PROCESSING,
    INDEXED,
    FAILED,
    CANCELED
}
