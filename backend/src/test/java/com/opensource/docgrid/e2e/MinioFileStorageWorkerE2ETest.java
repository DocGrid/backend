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
 * MinIO Adapter와 실제 자동 Worker 전체 관통 흐름을 격리 Bucket에서 검증한다.
 * Test가 만든 Bucket만 생성·삭제하고 기존 개발 Object에는 접근하지 않는다.
 */
@Tag("storage-worker-e2e")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MinIO 저장소와 Worker 전체 E2E")
class MinioFileStorageWorkerE2ETest extends AbstractFileStorageWorkerE2ETest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "docgrid_minio_storage_worker_e2e";
    private static final String TEST_BUCKET = "docgrid-minio-worker-" + RUN_ID;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        configureBaseEnvironment(
            registry,
            TEST_SCHEMA,
            TEST_BUCKET,
            StorageProvider.MINIO,
            "minio-storage-worker-e2e"
        );
        configureMinioConnection(registry);
    }

    @Override
    protected StorageProvider provider() {
        return StorageProvider.MINIO;
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
