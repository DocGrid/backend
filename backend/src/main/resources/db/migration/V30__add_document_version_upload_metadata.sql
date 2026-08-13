ALTER TABLE document_versions
    ADD COLUMN original_filename VARCHAR(500),
    ADD COLUMN content_type VARCHAR(200);

UPDATE document_versions dv
SET original_filename = fo.original_filename,
    content_type = fo.content_type
FROM file_objects fo
WHERE dv.file_object_id = fo.id;

CREATE UNIQUE INDEX uk_document_versions_one_in_progress
    ON document_versions (document_id)
    WHERE status IN ('UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING');
