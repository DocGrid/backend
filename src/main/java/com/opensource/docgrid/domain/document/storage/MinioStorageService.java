package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.global.config.MinioProperties;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService implements FileStorageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        try {
            ensureBucketExists();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, fileSize, -1)
                    .contentType(contentType)
                    .build()
            );
            return new StoredFile(minioProperties.getBucket(), objectKey);
        } catch (Exception e) {
            log.error("MinIO 파일 저장에 실패했습니다. bucket={}, objectKey={}",
                minioProperties.getBucket(), objectKey, e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    @Override
    public void delete(StoredFile storedFile) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(storedFile.bucketName())
                    .object(storedFile.objectKey())
                    .build()
            );
        } catch (Exception e) {
            log.error("MinIO 파일 삭제에 실패했습니다. bucket={}, objectKey={}",
                storedFile.bucketName(), storedFile.objectKey(), e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        BucketExistsArgs existsArgs = BucketExistsArgs.builder()
            .bucket(minioProperties.getBucket())
            .build();

        if (minioClient.bucketExists(existsArgs)) {
            return;
        }

        try {
            minioClient.makeBucket(
                MakeBucketArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build()
            );
        } catch (Exception e) {
            if (!minioClient.bucketExists(existsArgs)) {
                throw e;
            }
        }
    }
}
