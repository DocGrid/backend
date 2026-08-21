package com.opensource.docgrid.global.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * S3 Adapter가 선택된 환경에서만 AWS SDK Client를 구성한다.
 * 환경 Credential이 없으면 Profile과 EC2 Role을 지원하는 AWS 기본 Chain으로 위임한다.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "s3")
public class S3Config {

    private final S3Properties s3Properties;

    public S3Config(S3Properties s3Properties) {
        this.s3Properties = s3Properties;
    }

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(s3Properties.getRegion()))
            .credentialsProvider(resolveCredentialsProvider())
            .forcePathStyle(s3Properties.isPathStyleAccessEnabled());

        if (StringUtils.hasText(s3Properties.getEndpoint())) {
            // LocalStack이나 VPC Endpoint처럼 명시된 Endpoint만 기본 AWS Endpoint를 대체한다.
            builder.endpointOverride(URI.create(s3Properties.getEndpoint()));
        }
        return builder.build();
    }

    private AwsCredentialsProvider resolveCredentialsProvider() {
        boolean hasAccessKey = StringUtils.hasText(s3Properties.getAccessKey());
        boolean hasSecretKey = StringUtils.hasText(s3Properties.getSecretKey());
        if (!hasAccessKey && !hasSecretKey) {
            return DefaultCredentialsProvider.builder().build();
        }
        if (!hasAccessKey || !hasSecretKey) {
            throw new IllegalStateException("S3 Access Key와 Secret Key는 함께 설정해야 합니다.");
        }
        if (StringUtils.hasText(s3Properties.getSessionToken())) {
            return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                    s3Properties.getAccessKey(),
                    s3Properties.getSecretKey(),
                    s3Properties.getSessionToken()
                )
            );
        }
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3Properties.getAccessKey(), s3Properties.getSecretKey())
        );
    }
}
