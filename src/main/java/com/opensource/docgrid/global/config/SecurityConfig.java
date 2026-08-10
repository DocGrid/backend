package com.opensource.docgrid.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtAuthenticationFilter;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.mcp.security.McpApiKeyAuthFilter;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtProvider jwtProvider;
    private final McpAccessTokenCommandService mcpAccessTokenCommandService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/test/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/departments").permitAll()
                .requestMatchers("/auth/signup", "/auth/login").permitAll()
                // WebSocket 핸드셰이크는 여기서 인증하지 않는다. 네이티브 websocket Transport는
                // Upgrade 요청에 커스텀 헤더를 실을 수 없어, 인증은 StompAuthChannelInterceptor가
                // STOMP CONNECT 프레임에서 담당하고 목적지별 인가는 DashboardSubscriptionAuthorizationInterceptor가 담당한다.
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            /*
             * UsernamePasswordAuthenticationFilter는 위치 기준점(앵커)일 뿐이며,
             * 실제 목적은 두 필터(JwtAuthenticationFilter, McpApiKeyAuthFilter)가 최종 인증 판정(authorizeHttpRequests)보다 먼저 실행됨
             * SecurityContext를 채워두는 것이다. 각자 다른 경로만 처리하고 나머지는 스킵:
             *   - JwtAuthenticationFilter  → 웹 로그인(JWT), /mcp/tokens 등 일반 API 담당
             *   - McpApiKeyAuthFilter      → Claude Desktop API 키, /mcp 경로만 담당
             */
            .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new McpApiKeyAuthFilter(mcpAccessTokenCommandService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
