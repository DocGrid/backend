package com.opensource.docgrid.domain.embedding.dto.response;

/**
 * Batch 요청 위치와 그 위치의 Dense Vector를 결합하는 외부 서버 응답 항목이다.
 */
public record EmbedBatchItemResponse(int index, float[] vector) {

    public EmbedBatchItemResponse {
        vector = vector == null ? null : vector.clone();
    }

    @Override
    public float[] vector() {
        return vector == null ? null : vector.clone();
    }
}
