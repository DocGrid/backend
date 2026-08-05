package com.opensource.docgrid.domain.embedding.dto.response;

import java.util.List;

/**
 * Embedding Server가 실제 사용한 모델과 순서가 고정된 Batch 결과를 전달한다.
 */
public record EmbedBatchServerResponse(
    String model,
    List<EmbedBatchItemResponse> embeddings
) {

    public EmbedBatchServerResponse {
        embeddings = embeddings == null ? null : List.copyOf(embeddings);
    }
}
