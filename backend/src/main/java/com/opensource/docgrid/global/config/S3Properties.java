package com.opensource.docgrid.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS S3 Adapter의 Region, 선택적 Endpoint와 환경 주입 Credential을 제공한다.
 * 값이 없으면 Config가 AWS SDK 기본 Provider Chain으로 위임한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "s3")
public class S3Properties {

    private String region = "ap-northeast-2";
    private String endpoint;
    private boolean pathStyleAccessEnabled;
    private String accessKey;
    private String secretKey;
    private String sessionToken;
}
