package com.opensource.docgrid.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * MinIO Adapter의 SDK 연결 정보만 제공한다.
 * 공통 Bucket은 Provider 중립적인 FileStorageProperties에서 관리한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
}
