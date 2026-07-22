package com.opensource.docgrid.domain.embedding.service.query;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.dto.request.EmbedRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class QueryEmbeddingService {

    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final RestClient restClient;

    public QueryEmbeddingService(
        EmbeddingModelQueryService embeddingModelQueryService,
        @Qualifier("embeddingRestClient") RestClient restClient
    ) {
        this.embeddingModelQueryService = embeddingModelQueryService;
        this.restClient = restClient;
    }

    public EmbedResult embed(String text) {
        EmbeddingModel activeModel = embeddingModelQueryService.getActiveModel();

        EmbedServerResponse response;
        try {
            response = restClient.post()
                .uri("/embed")
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedServerResponse.class);
        } catch (RestClientException e) {
            log.error("임베딩 서버 호출 실패: {}", e.getMessage());
            throw new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
        }

        if (response == null
                || response.vector() == null
                || response.vector().length != activeModel.getDimension()
                || response.dimension() != activeModel.getDimension()) {
            log.error("임베딩 차원 불일치: expected={}, actual={}",
                activeModel.getDimension(), response == null ? "null" : response.dimension());
            throw new DocGridException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }

        return new EmbedResult(activeModel, response.vector());
    }
}
