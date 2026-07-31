package com.opensource.docgrid.domain.embedding.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.opensource.docgrid.domain.embedding.dto.request.EmbedRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedServerResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 Embedding Server의 단건 Vector 생성 HTTP 계약을 담당한다.
 *
 * <p>이 Client는 모델 선택과 Vector 차원 검증을 수행하지 않는다. 호출 Service가 실행 Context에 맞는
 * 모델을 선택하고 반환 Vector를 검증하며, 이 클래스는 전송 오류를 공통 서비스 장애로 변환하는 경계만
 * 책임진다.
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RestClient restClient;

    public EmbeddingClient(@Qualifier("embeddingRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 입력 Text를 외부 서버에 전달하고 Dense Vector를 반환한다.
     */
    public float[] embed(String text) {
        EmbedServerResponse response;
        try {
            response = restClient.post()
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
}
