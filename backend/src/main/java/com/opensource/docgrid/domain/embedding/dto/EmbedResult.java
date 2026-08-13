package com.opensource.docgrid.domain.embedding.dto;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;

public record EmbedResult(EmbeddingModel model, float[] vector) {
}
