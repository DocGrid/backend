package com.opensource.docgrid.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.opensource.docgrid.domain.auth.jwt.StompAuthChannelInterceptor;
import com.opensource.docgrid.domain.dashboard.websocket.DashboardSubscriptionAuthorizationInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 실시간 push를 위한 STOMP endpoint와 Message Broker 설정.
 *
 * <p>인증·인가는 이 설정이 아니라 {@link StompAuthChannelInterceptor}(CONNECT 시점 인증)와
 * {@link DashboardSubscriptionAuthorizationInterceptor}(SUBSCRIBE 시점 인가)가 담당한다.
 * 이 클래스는 전송 계층 구성(endpoint·broker·origin)과 두 Interceptor의 등록 순서만 책임진다.
 */
@EnableWebSocketMessageBroker
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final DashboardSubscriptionAuthorizationInterceptor dashboardSubscriptionAuthorizationInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(CorsConfig.ALLOWED_ORIGINS.toArray(new String[0]))
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 인증이 SUBSCRIBE 인가보다 먼저 Principal을 세션에 부착해야 하므로 순서를 고정한다.
        registration.interceptors(stompAuthChannelInterceptor, dashboardSubscriptionAuthorizationInterceptor);
    }
}
