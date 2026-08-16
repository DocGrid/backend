package com.opensource.docgrid.domain.embedding.client;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.opensource.docgrid.domain.embedding.dto.request.EmbedBatchRequest;
import com.opensource.docgrid.domain.embedding.dto.request.EmbedRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 Embedding Server의 단건·Batch Vector 생성 HTTP 계약을 담당한다.
 *
 * <p>검색 단건과 문서 Batch는 같은 Provider를 사용하지만 응답 시간 예산이 다르므로 전용 HTTP Client를
 * 분리한다. 모델 선택과 Vector 차원 검증은 호출 Service가 담당하며, 이 클래스는 요청 계약과 전송 오류
 * 변환 경계만 책임진다.
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RestClient queryRestClient;
    private final RestClient documentRestClient;

    public EmbeddingClient(
        @Qualifier("embeddingRestClient") RestClient queryRestClient,
        @Qualifier("documentEmbeddingRestClient") RestClient documentRestClient
    ) {
        this.queryRestClient = queryRestClient;
        this.documentRestClient = documentRestClient;
    }

    /**
     * 입력 Text를 외부 서버에 전달하고 Dense Vector를 반환한다.
     */
    public float[] embed(String text) {
        EmbedServerResponse response;
        try {
            response = queryRestClient.post()
                .uri("/embed")
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedServerResponse.class);
        } catch (RestClientException exception) {
            log.error("임베딩 서버 호출에 실패했습니다. cause={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
        }

        return response == null ? null : response.vector();
    }

    /**
     * 정렬된 Text 목록을 Batch API로 전달하고 모델·개수·순서 계약을 검증한다.
     */
    public EmbedBatchServerResponse embedBatch(List<String> texts, int batchSize) {
        EmbedBatchServerResponse response;
        try {
            response = documentRestClient.post()
                .uri("/embed/batch")
                .body(new EmbedBatchRequest(texts, batchSize))
                .retrieve()
                .body(EmbedBatchServerResponse.class);
        } catch (RestClientException exception) {
            log.error("임베딩 서버 Batch 호출에 실패했습니다. cause={}", exception.getClass().getSimpleName());
            throw new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
        }

        validateBatchResponse(response, texts == null ? -1 : texts.size());
        return response;
    }

    private void validateBatchResponse(EmbedBatchServerResponse response, int expectedCount) {
        if (response == null
            || !StringUtils.hasText(response.model())
            || response.embeddings() == null
            || response.embeddings().size() != expectedCount) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        for (int index = 0; index < response.embeddings().size(); index++) {
            EmbedBatchItemResponse item = response.embeddings().get(index);
            if (item == null || item.index() != index) {
                throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
            }
        }
    }
}
