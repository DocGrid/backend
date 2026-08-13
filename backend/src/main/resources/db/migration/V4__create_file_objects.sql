-- file_objects: 실제 파일 바이너리 저장 위치(MinIO/S3/local) 및 해시 기록 테이블 (FileObject 엔티티 대응)
CREATE TABLE file_objects (
    id BIGSERIAL PRIMARY KEY,

    bucket_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(1000) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    content_type VARCHAR(200),
    file_size BIGINT NOT NULL,
    file_hash VARCHAR(128) NOT NULL,
    storage_provider VARCHAR(20) NOT NULL,

    -- 파일을 업로드한 사용자 (-> users.id)
    uploaded_by BIGINT REFERENCES users (id),
    uploaded_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_file_objects_storage_provider_bucket_name_object_key
        UNIQUE (storage_provider, bucket_name, object_key)
);

CREATE INDEX idx_file_objects_file_hash_file_size ON file_objects (file_hash, file_size);
CREATE INDEX idx_file_objects_uploaded_by ON file_objects (uploaded_by);
