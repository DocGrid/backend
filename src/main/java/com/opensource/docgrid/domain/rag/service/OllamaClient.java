package com.opensource.docgrid.domain.rag.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.request.OllamaGenerateRequest;
import com.opensource.docgrid.domain.rag.dto.response.OllamaGenerateResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Ollama {@code /api/generate}를 호출해 프롬프트로부터 답변을 생성하는 순수 HTTP 클라이언트 (F-RAG-02).
 *
 * <p>검색 결과가 있는지, LLM 호출을 생략할지(NO_CONTEXT) 판단하지 않는다 — 항상 주어진 프롬프트를 그대로
 * 전송한다. 그 판단은 이 클라이언트를 호출하는 쪽(RagFacade)의 책임이다.
 */
@Slf4j
@Service
public class OllamaClient {

    private final String model;
    private final RestClient restClient;

    public OllamaClient(
        @Value("${ollama.model}") String model,
        @Qualifier("ollamaRestClient") RestClient restClient
    ) {
        this.model = model;
        this.restClient = restClient;
    }

    public OllamaGenerateResult generate(String prompt) {
        long start = System.currentTimeMillis();

        OllamaGenerateResponse response;
        try {
            response = restClient.post()
                .uri("/api/generate")
                .body(new OllamaGenerateRequest(model, prompt, false))
                .retrieve()
                .body(OllamaGenerateResponse.class);
        } catch (RestClientException e) {
            log.error("Ollama 서버 호출 실패: {}", e.getMessage());
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        if (response == null || response.response() == null) {
            log.error("Ollama 응답이 비어있음: response={}", response);
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        int latencyMs = (int) (System.currentTimeMillis() - start);
        return new OllamaGenerateResult(
            response.model(), response.response(), response.promptEvalCount(), response.evalCount(), latencyMs
        );
    }
}
