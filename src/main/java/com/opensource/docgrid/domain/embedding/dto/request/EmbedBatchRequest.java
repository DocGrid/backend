package com.opensource.docgrid.domain.embedding.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 문서 Chunk Text 목록과 Embedding Server 내부 Batch 크기를 전달하는 불변 요청 계약이다.
 */
public record EmbedBatchRequest(
    List<String> texts,
    @JsonProperty("batch_size") int batchSize
) {

    public EmbedBatchRequest {
        texts = texts == null ? null : List.copyOf(texts);
    }
}
