package com.opensource.docgrid.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;

/**
 * MinIO Adapter가 선택된 환경에서만 MinIO SDK Client를 구성한다.
 * Local Filesystem 등 다른 Adapter는 MinIO endpoint나 Credential을 요구하지 않는다.
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MinioProperties.class)
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "minio")
public class MinioConfig {

    private final MinioProperties minioProperties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
            .endpoint(minioProperties.getEndpoint())
            .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
            .build();
    }
}
