package com.opensource.docgrid.domain.document.integration.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;

/**
 * 실제 MinIO에서 Object 전체 읽기와 Bucket 설정 불일치 및 파일 없음 오류를 검증한다.
 *
 * <p>설정 Bucket에서는 실제 응답 Byte를 읽고, 별도 Bucket에 Object가 있어도 현재 환경과 다른
 * Snapshot은 원격 조회 전에 거부하는지 확인한다.
 */
@Tag("integration")
@Tag("minio-integration")
@SpringBootTest
@ActiveProfiles({"test", "minio-integration"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("실제 MinIO Document 읽기 통합 테스트")
class MinioDocumentReadIntegrationTest {

    private static final String DEFAULT_BUCKET = "docgrid-read-"
        + UUID.randomUUID().toString().replace("-", "");
    private static final String OTHER_BUCKET = DEFAULT_BUCKET + "-other";

    @Autowired private FileStorageService fileStorageService;
    @Autowired private MinioClient minioClient;

    @DynamicPropertySource
    static void configureBucket(DynamicPropertyRegistry registry) {
        registry.add("storage.bucket", () -> DEFAULT_BUCKET);
        registry.add("jwt.secret", () -> "docgrid-minio-read-integration-test-secret-key-2026");
    }

    @BeforeAll
    void createBuckets() throws Exception {
        createBucket(DEFAULT_BUCKET);
        createBucket(OTHER_BUCKET);
    }

    @AfterAll
    void deleteBuckets() throws Exception {
        removeObject(DEFAULT_BUCKET, "default.txt");
        removeObject(OTHER_BUCKET, "other.txt");
        removeBucket(DEFAULT_BUCKET);
        removeBucket(OTHER_BUCKET);
    }

    @Test
    @DisplayName("실제 MinIO에 저장한 TXT Byte를 그대로 읽는다")
    void read_returnsStoredBytes() {
        byte[] content = "DocGrid 실제 MinIO 읽기".getBytes(StandardCharsets.UTF_8);
        StoredFile storedFile = fileStorageService.store(
            new ByteArrayInputStream(content),
            content.length,
            "text/plain",
            "default.txt"
        );

        assertThat(fileStorageService.read(storedFile)).isEqualTo(content);
    }

    @Test
    @DisplayName("Object가 있어도 현재 설정과 다른 Bucket Snapshot은 거부한다")
    void read_rejectsStoredFileFromDifferentBucket() throws Exception {
        byte[] content = "다른 Bucket 원본".getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(OTHER_BUCKET)
            .object("other.txt")
            .stream(new ByteArrayInputStream(content), content.length, -1)
            .contentType("text/plain")
            .build());

        assertThatThrownBy(() -> fileStorageService.read(
            new StoredFile(StorageProvider.MINIO, OTHER_BUCKET, "other.txt")
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
    }

    @Test
    @DisplayName("실제 MinIO에 없는 Object는 파일 없음 오류다")
    void read_throwsNotFoundForMissingObject() {
        assertThatThrownBy(() -> fileStorageService.read(
            new StoredFile(StorageProvider.MINIO, DEFAULT_BUCKET, "missing.txt")
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_OBJECT_NOT_FOUND));
    }

    private void createBucket(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void removeObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
        } catch (Exception ignored) {
            // 테스트가 Object 저장 전에 실패한 경우에도 Bucket 정리를 계속한다.
        }
    }

    private void removeBucket(String bucket) {
        try {
            minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        } catch (Exception ignored) {
            // 정리 실패는 원래 테스트 결과를 가리지 않으며 Bucket 이름이 실행마다 달라 충돌하지 않는다.
        }
    }
}
