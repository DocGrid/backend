package com.opensource.docgrid.global.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Ollama HTTP 연결과 추론 응답 제한 시간을 실행 환경별로 구성한다.
 *
 * <p>RAG 도메인은 Timeout 이후의 검색 결과 Fallback을 책임지고, 이 설정은 Sites 요청 제한보다
 * 먼저 호출을 종료할 수 있는 Transport 경계만 책임진다.</p>
 */
@Configuration
public class OllamaServerConfig {

    @Value("${ollama.server.base-url}")
    private String baseUrl;

    @Value("${ollama.server.connect-timeout:5s}")
    private Duration connectTimeout;

    @Value("${ollama.server.read-timeout:20s}")
    private Duration readTimeout;

    /**
     * 실행 환경별 제한 시간을 적용한 Ollama 전용 Client를 만든다.
     */
    @Bean("ollamaRestClient")
    public RestClient ollamaRestClient() {
        // 1. 연결 실패와 느린 추론을 서로 다른 제한 시간으로 중단한다.
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        // 2. RAG Client가 동일한 Transport 정책을 사용하도록 이름이 지정된 Client를 제공한다.
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
