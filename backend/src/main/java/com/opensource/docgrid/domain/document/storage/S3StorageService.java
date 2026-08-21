package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * AWS SDK v2를 사용해 문서 원본을 저장·조회·삭제하는 파일 저장소 Adapter다.
 * Bucket 생성과 권한 관리는 인프라 경계에 두고 애플리케이션은 Object 작업만 수행한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "s3")
public class S3StorageService implements FileStorageService {

    private final S3Client s3Client;
    private final String bucketName;

    public S3StorageService(S3Client s3Client, FileStorageProperties fileStorageProperties) {
        this.s3Client = s3Client;
        this.bucketName = requireBucket(fileStorageProperties.getBucket());
    }

    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .build();
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, fileSize));
            return new StoredFile(StorageProvider.S3, bucketName, objectKey);
        } catch (Exception exception) {
            log.error("S3 파일 저장에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    @Override
    public byte[] read(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(storedFile.bucketName())
                .key(storedFile.objectKey())
                .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
        } catch (S3Exception exception) {
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
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(storedFile.bucketName())
                    .key(storedFile.objectKey())
                    .build()
            );
        } catch (Exception exception) {
            log.error("S3 파일 삭제에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    private boolean isObjectNotFound(S3Exception exception) {
        if (exception.statusCode() == 404) {
            return true;
        }
        if (exception.awsErrorDetails() == null) {
            return false;
        }
        String errorCode = exception.awsErrorDetails().errorCode();
        return "NoSuchKey".equals(errorCode) || "NoSuchObject".equals(errorCode);
    }

    private String requireBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException("S3 Adapter에는 STORAGE_BUCKET 설정이 필요합니다.");
        }
        return configuredBucket;
    }

    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.S3) {
            return;
        }
        log.error("현재 S3 Adapter와 파일 Provider가 일치하지 않습니다. storedProvider={}",
            storedFile.storageProvider());
        throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED);
    }

    private void logStorageReadFailure(Exception exception) {
        // Bucket과 Object Key는 내부 식별 정보이므로 장애 로그에는 예외 원인만 남긴다.
        log.error("S3 파일 읽기에 실패했습니다.", exception);
    }
}
