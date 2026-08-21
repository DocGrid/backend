package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.LocalFileStorageService;
import com.opensource.docgrid.domain.document.storage.S3StorageService;

import io.minio.MinioClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 저장소 선택 시 Spring이 S3 Adapter와 Client만 등록하는지 통합 검증한다.
 * 실제 Object I/O는 수행하지 않아 외부 AWS 계정과 Network에 의존하지 않는다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "storage.type=s3",
    "s3.endpoint=http://127.0.0.1:4566",
    "s3.access-key=test-access-key",
    "s3.secret-key=test-secret-key"
})
@DisplayName("S3 저장소 Spring Context 통합 테스트")
class S3StorageContextIntegrationTest {

    @Autowired private ApplicationContext applicationContext;

    @Test
    @DisplayName("S3 Adapter와 Client만 파일 저장소 Bean으로 등록한다")
    void context_registersOnlyS3StorageAdapter() {
        assertThat(applicationContext.getBean(FileStorageService.class))
            .isInstanceOf(S3StorageService.class);
        assertThat(applicationContext.getBeansOfType(S3Client.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MinioClient.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(LocalFileStorageService.class)).isEmpty();
    }
}
