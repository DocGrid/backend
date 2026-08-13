package com.opensource.docgrid.domain.embedding.enums;

/**
 * 임베딩을 생성하는 모델 제공자.
 */
public enum EmbeddingProvider {
    OPENAI,
    UPSTAGE,
    LOCAL,
    MOCK,
    HUGGINGFACE
}
