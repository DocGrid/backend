DROP INDEX idx_file_objects_file_hash_file_size;

ALTER TABLE file_objects
    ADD CONSTRAINT uk_file_objects_file_hash_file_size
        UNIQUE (file_hash, file_size);
