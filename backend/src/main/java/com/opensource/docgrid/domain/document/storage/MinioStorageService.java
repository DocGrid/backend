package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO SDK를 사용해 문서 원본을 저장·조회·삭제하는 파일 저장소 Adapter다.
 * MinIO가 선택된 환경에서만 등록되며 공통 Bucket과 Object Key를 저장 위치로 반환하고 검증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "minio")
public class MinioStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final FileStorageProperties fileStorageProperties;

    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        try {
            ensureBucketExists();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(fileStorageProperties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, fileSize, -1)
                    .contentType(contentType)
                    .build()
            );
            return new StoredFile(StorageProvider.MINIO, fileStorageProperties.getBucket(), objectKey);
        } catch (Exception e) {
            log.error("MinIO 파일 저장에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    @Override
    public byte[] read(StoredFile storedFile) {
        validateLocation(storedFile);
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(storedFile.bucketName())
                .object(storedFile.objectKey())
                .build()
        )) {
            // Service 안에서 Stream을 모두 읽고 닫아 호출자가 MinIO 연결 Resource를 소유하지 않게 한다.
            return inputStream.readAllBytes();
        } catch (ErrorResponseException exception) {
            if (isObjectNotFound(exception)) {
                throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
            }
            logStorageReadFailure(exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        } catch (Exception exception) {
            logStorageReadFailure(exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    @Override
    public void delete(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(storedFile.bucketName())
                    .object(storedFile.objectKey())
                    .build()
            );
        } catch (Exception e) {
            log.error("MinIO 파일 삭제에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        BucketExistsArgs existsArgs = BucketExistsArgs.builder()
            .bucket(fileStorageProperties.getBucket())
            .build();

        if (minioClient.bucketExists(existsArgs)) {
            return;
        }

        try {
            minioClient.makeBucket(
                MakeBucketArgs.builder()
                    .bucket(fileStorageProperties.getBucket())
                    .build()
            );
        } catch (Exception e) {
            if (!minioClient.bucketExists(existsArgs)) {
                throw e;
            }
        }
    }

    private boolean isObjectNotFound(ErrorResponseException exception) {
        String errorCode = exception.errorResponse().code();
        return "NoSuchKey".equals(errorCode) || "NoSuchObject".equals(errorCode);
    }

    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.MINIO
            && fileStorageProperties.getBucket().equals(storedFile.bucketName())) {
            return;
        }
        log.error("현재 MinIO 저장소 설정과 파일 위치가 일치하지 않습니다.");
        throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
    }

    private void logStorageReadFailure(Exception exception) {
        // 원본 저장 위치는 내부 식별 정보이므로 장애 로그에는 예외 원인만 남긴다.
        log.error("MinIO 파일 읽기에 실패했습니다.", exception);
    }
}
