package com.opensource.docgrid.e2e;

import java.util.ArrayList;
import java.util.List;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;

/**
 * 로컬 전체 관통 E2E의 격리 Bucket 생성·Object 확인·정리를 담당한다.
 *
 * <p>제품 Storage Bean은 실제 MinIO Client를 그대로 사용하고 이 Fixture는 Test 수명 밖의 Object가
 * 남지 않게 하는 환경 경계만 책임진다.
 */
final class LocalE2eMinioBucket implements AutoCloseable {

    private final MinioClient minioClient;
    private final String bucket;

    LocalE2eMinioBucket(MinioClient minioClient, String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    void create() throws Exception {
        boolean exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(bucket).build()
        );
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    StatObjectResponse stat(String objectKey) throws Exception {
        return minioClient.statObject(
            StatObjectArgs.builder().bucket(bucket).object(objectKey).build()
        );
    }

    List<String> objectKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
            ListObjectsArgs.builder().bucket(bucket).recursive(true).build()
        );
        for (Result<Item> result : results) {
            keys.add(result.get().objectName());
        }
        return List.copyOf(keys);
    }

    @Override
    public void close() throws Exception {
        boolean exists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(bucket).build()
        );
        if (!exists) {
            return;
        }

        // 1. MinIO는 비어 있지 않은 Bucket 삭제를 거부하므로 실제 Object를 모두 먼저 제거한다.
        for (String objectKey : objectKeys()) {
            minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build()
            );
        }

        // 2. Test가 생성한 격리 Bucket만 삭제하고 다른 개발 Bucket은 조회하거나 변경하지 않는다.
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }
}
