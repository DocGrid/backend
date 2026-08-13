package com.opensource.docgrid.domain.embedding.service.query;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class QueryEmbeddingService {

    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final EmbeddingClient embeddingClient;

    public QueryEmbeddingService(
        EmbeddingModelQueryService embeddingModelQueryService,
        EmbeddingClient embeddingClient
    ) {
        this.embeddingModelQueryService = embeddingModelQueryService;
        this.embeddingClient = embeddingClient;
    }

    public EmbedResult embed(String text) {
        EmbeddingModel activeModel = embeddingModelQueryService.getActiveModel();
        float[] vector = embeddingClient.embed(text);

        if (vector == null || vector.length != activeModel.getDimension()) {
            int actual = vector == null ? -1 : vector.length;
            log.error("임베딩 차원 불일치: expected={}, actual={}", activeModel.getDimension(), actual);
            throw new DocGridException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }

        return new EmbedResult(activeModel, vector);
    }
}
