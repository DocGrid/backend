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
 * Local Filesystem Adapter와 실제 자동 Worker 전체 관통 흐름을 격리 Directory에서 검증한다.
 * 외부 Object Storage를 사용하지 않으며 Test가 만든 Root만 종료 시 제거한다.
 */
@Tag("storage-worker-e2e")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Local Filesystem 저장소와 Worker 전체 E2E")
class LocalFileStorageWorkerE2ETest extends AbstractFileStorageWorkerE2ETest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String TEST_SCHEMA = "docgrid_local_storage_worker_e2e";
    private static final String TEST_BUCKET = "docgrid-local-worker-e2e";
    private static final Path TEST_ROOT = Path.of("build", "storage-worker-e2e", "local-" + RUN_ID);

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        configureBaseEnvironment(
            registry,
            TEST_SCHEMA,
            TEST_BUCKET,
            StorageProvider.LOCAL,
            "local-storage-worker-e2e"
        );
        registry.add("storage.local.root", () -> TEST_ROOT.toString());
    }

    @Override
    protected StorageProvider provider() {
        return StorageProvider.LOCAL;
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
        return false;
    }

    @Override
    protected Path localRoot() {
        return TEST_ROOT;
    }
}
