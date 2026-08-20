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
 * <p>(#218 이전) RAG 도메인은 Timeout 이후의 검색 결과 Fallback을 책임지고, 이 설정은 프론트의 29초
 * 검색 제한과 Sites의 30초 요청 제한보다 먼저 호출을 종료할 수 있는 Transport 경계만 책임졌다.
 *
 * <p>(#218 이후) 검색-RAG가 비동기 Job 큐로 전환되면서 프론트가 이 호출을 동기로 기다리지 않는다 —
 * 위 29초/30초 제약 자체가 사라졌다. 지금 이 설정의 read-timeout은 "정해진 시간 안에 끝나야 하는
 * 제약"이 아니라, Ollama가 완전히 응답하지 않을 때 스트림 연결이 무한정 남지 않도록 하는 최후
 * 안전장치 역할이다. 실질적인 생성 시간 상한은 {@code ollama.generate-deadline}(RagJobWorker가
 * 감시)이 담당한다.</p>
 */
@Configuration
public class OllamaServerConfig {

    @Value("${ollama.server.base-url}")
    private String baseUrl;

    @Value("${ollama.server.connect-timeout:3s}")
    private Duration connectTimeout;

    /*
     * application.yml이 이 값을 항상 제공하므로 이 인라인 기본값은 실행 시 도달하지 않지만,
     * connectTimeout과 마찬가지로 yml 기본값(90s)과 일치시켜 둔다.
     */
    @Value("${ollama.server.read-timeout:90s}")
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
