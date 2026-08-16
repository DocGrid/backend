package com.opensource.docgrid.global.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Embedding Provider HTTP 연결 설정을 구성한다.
 *
 * <p>Provider 호출 계약과 오류 분류는 Domain Client가 담당하고, 이 설정은 연결·응답 제한 시간과
 * Base URL 같은 Transport 경계만 책임진다.</p>
 */
@Configuration
public class EmbeddingServerConfig {

    @Value("${embedding.server.base-url}")
    private String baseUrl;

    @Value("${embedding.server.connect-timeout:5s}")
    private Duration connectTimeout;

    @Value("${embedding.server.read-timeout:5s}")
    private Duration readTimeout;

    @Value("${embedding.document.read-timeout:30s}")
    private Duration documentReadTimeout;

    /**
     * 실행 환경별 제한 시간을 적용한 Embedding Provider 전용 Client를 만든다.
     */
    @Bean("embeddingRestClient")
    public RestClient embeddingRestClient() {
        return buildRestClient(readTimeout);
    }

    /**
     * 실제 문서 길이의 Batch 추론 시간을 허용하는 문서 인덱싱 전용 Client를 만든다.
     */
    @Bean("documentEmbeddingRestClient")
    public RestClient documentEmbeddingRestClient() {
        return buildRestClient(documentReadTimeout);
    }

    private RestClient buildRestClient(Duration configuredReadTimeout) {
        // 1. 연결 제한은 공유하되 단건 검색과 문서 Batch가 서로 다른 응답 시간 예산을 사용하게 한다.
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(configuredReadTimeout);

        // 2. 두 Client가 Base URL과 연결 정책은 공유하면서 용도별 Read Timeout만 분리하게 한다.
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
