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

    /**
     * 실행 환경별 제한 시간을 적용한 Embedding Provider 전용 Client를 만든다.
     */
    @Bean("embeddingRestClient")
    public RestClient embeddingRestClient() {
        // 1. 연결과 추론 응답 제한을 분리해 느린 CPU 추론 환경에서도 Timeout을 독립적으로 조정한다.
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        // 2. Domain Client가 같은 Transport 정책을 공유하도록 단일 이름의 RestClient를 제공한다.
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
