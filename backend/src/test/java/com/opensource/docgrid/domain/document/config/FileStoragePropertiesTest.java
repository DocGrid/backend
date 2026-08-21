package com.opensource.docgrid.domain.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 파일 저장소 Adapter 설정이 현재 구현 범위의 값만 허용하는지 검증한다.
 */
@DisplayName("FileStorageProperties 테스트")
class FileStoragePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("local과 minio 설정은 해당 Adapter 종류로 바인딩된다")
    void storageType_bindsSupportedAdapters() {
        contextRunner.withPropertyValues("storage.type=minio")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FileStorageProperties.class).getType())
                    .isEqualTo(FileStorageType.MINIO);
            });
    }

    @Test
    @DisplayName("구현되지 않은 S3 설정은 애플리케이션 시작 단계에서 거부된다")
    void storageType_rejectsUnsupportedAdapter() {
        contextRunner.withPropertyValues("storage.type=s3")
            .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 테스트 대상 ConfigurationProperties만 등록해 설정 바인딩 경계를 격리한다.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileStorageProperties.class)
    static class TestConfig {
    }
}
