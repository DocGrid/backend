package com.opensource.docgrid.global.config;

import java.util.List;

import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import lombok.RequiredArgsConstructor;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
@RequiredArgsConstructor
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {

        SecurityScheme accessTokenAuth = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .in(SecurityScheme.In.HEADER)
            .name("Authorization");

        SecurityRequirement securityRequirement = new SecurityRequirement()
            .addList("accessTokenAuth");

        Server localServer = new Server()
            .url("http://localhost:8080")
            .description("로컬 서버");

        Server awsServer = new Server()
            .url("http://52.79.212.118:8080")
            .description("운영 서버 (AWS)");

        return new OpenAPI()
            .info(new Info()
                .title("DocGrid API Documentation")
                .description("DocGrid 프로젝트 API 명세서입니다.")
                .version("v1.0.0"))
            .components(new Components()
                .addSecuritySchemes("accessTokenAuth", accessTokenAuth))
            .addSecurityItem(securityRequirement)
            .servers(List.of(localServer, awsServer));
    }

    @Bean
    @Primary
    public SwaggerUiConfigProperties swaggerUiConfigProperties(SwaggerUiConfigProperties props) {
        props.setPersistAuthorization(true);
        return props;
    }
}
