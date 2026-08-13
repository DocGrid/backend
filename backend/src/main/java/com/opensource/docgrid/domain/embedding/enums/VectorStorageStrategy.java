package com.opensource.docgrid.domain.embedding.enums;

/**
 * 서로 다른 차원/모델의 벡터를 저장하는 전략.
 * SINGLE_DIMENSION: 단일 차원 고정, MODEL_PARTITION: 모델별 파티션 분리, MODEL_TABLE: 모델별 별도 테이블.
 */
public enum VectorStorageStrategy {
    SINGLE_DIMENSION,
    MODEL_PARTITION,
    MODEL_TABLE
}
