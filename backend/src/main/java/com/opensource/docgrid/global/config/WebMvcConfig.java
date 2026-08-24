package com.opensource.docgrid.global.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.mcp.security.McpApiKeyAuthFilter;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;

/**
 * Spring MVC 확장 설정.
 *
 * <p>{@link CurrentUserArgumentResolver}를 등록해 컨트롤러가 {@code @CurrentUser}로 인증된
 * 사용자 ID를 받을 수 있게 하고, {@code /mcp}를 제외한 나머지 경로에 OSIV(Open Session In
 * View) 인터셉터를 등록한다. {@code /mcp}는 MCP Streamable HTTP 응답이 비동기 재디스패치로
 * 처리돼 OSIV가 요청 종료 신호를 못 받아 DB 커넥션이 반납되지 않는 문제(#120)가 있어,
 * {@code spring.jpa.open-in-view=false}로 전역 OSIV를 끈 뒤 이 클래스가 {@code /mcp}만
 * 뺀 나머지 경로에 직접 재등록한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * {@code /mcp}를 제외한 모든 경로에 OSIV 인터셉터를 등록한다.
     *
     * <p>{@link EntityManagerFactory}를 생성자 필드 대신 {@link ObjectProvider}로 받는 이유:
     * {@code @WebMvcTest} 슬라이스는 JPA 계층을 안 올려 이 빈이 없는데도, 이 클래스가
     * {@link WebMvcConfigurer} 구현체라는 이유만으로 스캔 대상이 되어 컨텍스트 로딩이
     * 실패한다. 빈이 없으면 인터셉터 등록 자체를 건너뛰어 이 문제를 피한다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
        if (entityManagerFactory == null) {
            return;
        }
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        // McpApiKeyAuthFilter가 판단하는 /mcp 경로와 동일한 상수를 참조해 두 곳이
        // 서로 다른 경로 문자열로 어긋나지 않게 한다.
        registry.addWebRequestInterceptor(interceptor).excludePathPatterns(McpApiKeyAuthFilter.MCP_ENDPOINT);
    }
}
