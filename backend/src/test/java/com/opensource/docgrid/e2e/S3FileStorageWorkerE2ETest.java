package com.opensource.docgrid.e2e;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.document.enums.StorageProvider;

/**
 * S3 Adapter와 실제 자동 Worker 전체 관통 흐름을 S3-compatible 격리 Bucket에서 검증한다.
 * AWS 계정 없이도 같은 AWS SDK Object 계약을 반복 실행할 수 있게 Local MinIO Endpoint를 사용한다.
 */
@Tag("storage-worker-e2e")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("S3 저장소와 Worker 전체 E2E")
class S3FileStorageWorkerE2ETest extends AbstractFileStorageWorkerE2ETest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "docgrid_s3_storage_worker_e2e_" + RUN_ID.substring(0, 12);
    private static final String TEST_BUCKET = "docgrid-s3-worker-" + RUN_ID;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        configureBaseEnvironment(
            registry,
            TEST_SCHEMA,
            TEST_BUCKET,
            StorageProvider.S3,
            "s3-storage-worker-e2e"
        );
        configureS3CompatibleConnection(registry);
    }

    @Override
    protected StorageProvider provider() {
        return StorageProvider.S3;
    }

    @Override
    protected String schema() {
        return TEST_SCHEMA;
    }

    @Override
    protected String bucket() {
        return TEST_BUCKET;
    }

    @Override
    protected boolean usesRemoteBucket() {
        return true;
    }

    @Override
    protected Path localRoot() {
        return null;
    }
}
