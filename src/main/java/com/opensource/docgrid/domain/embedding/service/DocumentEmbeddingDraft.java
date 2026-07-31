package com.opensource.docgrid.domain.embedding.service;

import java.util.Arrays;

/**
 * 외부 호출로 생성돼 아직 영속화되지 않은 단일 Chunk Embedding의 불변 값을 전달한다.
 *
 * <p>완료 Transaction은 Chunk ID·순서·내용 Hash를 준비 Snapshot과 다시 비교하고 Vector 차원,
 * 유한 값과 Hash를 검증한 뒤에만 이 값을 Entity로 변환한다.
 */
public record DocumentEmbeddingDraft(
    Long chunkId,
    int chunkIndex,
    String contentHash,
    float[] vector,
    String vectorHash
) {

    public DocumentEmbeddingDraft {
        vector = copyVector(vector);
    }

    @Override
    public float[] vector() {
        return copyVector(vector);
    }

    private static float[] copyVector(float[] source) {
        return source == null ? null : Arrays.copyOf(source, source.length);
    }
}
