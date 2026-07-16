package com.opensource.docgrid.domain.document.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.document.entity.FileObject;

public interface FileObjectRepository extends JpaRepository<FileObject, Long> {

    Optional<FileObject> findByFileHashAndFileSize(String fileHash, Long fileSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO file_objects (
            bucket_name, object_key, original_filename, content_type,
            file_size, file_hash, storage_provider, uploaded_by, uploaded_at,
            created_at, updated_at
        ) VALUES (
            :bucketName, :objectKey, :originalFilename, :contentType,
            :fileSize, :fileHash, 'MINIO', :uploadedBy, CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
        ON CONFLICT (file_hash, file_size) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("bucketName") String bucketName,
        @Param("objectKey") String objectKey,
        @Param("originalFilename") String originalFilename,
        @Param("contentType") String contentType,
        @Param("fileSize") Long fileSize,
        @Param("fileHash") String fileHash,
        @Param("uploadedBy") Long uploadedBy
    );
}
