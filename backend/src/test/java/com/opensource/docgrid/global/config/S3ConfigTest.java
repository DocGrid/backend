package com.opensource.docgrid.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 설정이 Region과 선택적 Endpoint를 AWS SDK Client에 전달하는지 검증한다.
 * Credential 해석과 실제 S3 연결은 AWS SDK 기본 Chain과 별도 통합 검증의 경계로 둔다.
 */
@DisplayName("S3Config 테스트")
class S3ConfigTest {

    @Test
    @DisplayName("설정된 Region과 Endpoint Override로 S3 Client를 생성한다")
    void s3Client_usesConfiguredRegionAndEndpoint() {
        S3Properties properties = new S3Properties();
        properties.setRegion("ap-northeast-2");
        properties.setEndpoint("http://127.0.0.1:4566");
        properties.setPathStyleAccessEnabled(true);

        try (S3Client s3Client = new S3Config(properties).s3Client()) {
            assertThat(s3Client.serviceClientConfiguration().region())
                .isEqualTo(Region.AP_NORTHEAST_2);
            assertThat(s3Client.serviceClientConfiguration().endpointOverride())
                .contains(URI.create(properties.getEndpoint()));
        }
    }

    @Test
    @DisplayName("Access Key와 Secret Key 중 하나만 설정하면 시작을 거부한다")
    void s3Client_throws_when_staticCredentialsAreIncomplete() {
        S3Properties properties = new S3Properties();
        properties.setAccessKey("access-key-only");

        assertThatThrownBy(() -> new S3Config(properties).s3Client())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("함께 설정");
    }
}
