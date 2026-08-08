package com.opensource.docgrid.global.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    // spring.jpa.open-in-view=false로 전역 OSIV를 끄고, /mcp를 제외한 나머지 경로에만
    // 다시 직접 등록한다. /mcp는 MCP Streamable HTTP 응답 처리 방식이 요청 완료 시점을
    // 제대로 신호하지 못해 OSIV가 커넥션을 반납하지 못하고 누수되는 문제(#120)가 있었다.
    // ObjectProvider로 주입받는 이유: @WebMvcTest 슬라이스는 EntityManagerFactory 빈이
    // 없는데도 WebMvcConfigurer 구현체라 스캔 대상이 되므로, 없으면 등록을 건너뛰게 한다.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
        if (entityManagerFactory == null) {
            return;
        }
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        registry.addWebRequestInterceptor(interceptor).excludePathPatterns("/mcp");
    }
}
