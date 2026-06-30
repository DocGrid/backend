package com.opensource.docgrid.global.config;

import java.util.List;

import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SwaggerConfig {

    private final Environment env;

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

        Server server = new Server();
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            server.setUrl("https://docgrid.com");
            server.setDescription("운영 서버");
        } else {
            server.setUrl("http://localhost:8080");
            server.setDescription("로컬 서버");
        }

        return new OpenAPI()
            .info(new Info()
                .title("DocGrid API Documentation")
                .description("DocGrid 프로젝트 API 명세서입니다.")
                .version("v1.0.0"))
            .components(new Components()
                .addSecuritySchemes("accessTokenAuth", accessTokenAuth))
            .addSecurityItem(securityRequirement)
            .servers(List.of(server));
    }

    @Bean
    @Primary
    public SwaggerUiConfigProperties swaggerUiConfigProperties(SwaggerUiConfigProperties props) {
        props.setPersistAuthorization(true);
        return props;
    }
}
