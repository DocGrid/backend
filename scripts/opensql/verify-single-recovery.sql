\set ON_ERROR_STOP on

-- 실행자가 검증한 식별자만 Search Path와 Session 설정에 전달해 동적 SQL 문자열 조합을 피한다.
SET search_path TO :"db_schema", public;
SELECT set_config('docgrid.recovery.document_id', :'document_id', false);
SELECT set_config('docgrid.recovery.document_version_id', :'document_version_id', false);
SELECT set_config('docgrid.recovery.job_id', :'job_id', false);

-- 1. 장애 전 Claim된 같은 Job이 Retry 뒤 현재 Version과 함께 INDEXED로 수렴했는지 검증한다.
DO $docgrid_recovery$
DECLARE
    target_document_id BIGINT := current_setting('docgrid.recovery.document_id')::BIGINT;
    target_version_id BIGINT := current_setting('docgrid.recovery.document_version_id')::BIGINT;
    target_job_id BIGINT := current_setting('docgrid.recovery.job_id')::BIGINT;
    document_status VARCHAR(20);
    current_version_id BIGINT;
    version_status VARCHAR(20);
    job_status VARCHAR(20);
    job_retry_count INTEGER;
    job_model_id BIGINT;
    chunk_count BIGINT;
    embedding_count BIGINT;
    duplicate_chunk_count BIGINT;
    duplicate_embedding_count BIGINT;
    duplicate_outbox_count BIGINT;
BEGIN
    SELECT d.status, d.current_version_id, dv.status, ej.status, ej.retry_count, ej.embedding_model_id
    INTO document_status, current_version_id, version_status, job_status, job_retry_count, job_model_id
    FROM documents d
    JOIN document_versions dv ON dv.id = target_version_id AND dv.document_id = d.id
    JOIN embedding_jobs ej ON ej.id = target_job_id AND ej.document_version_id = dv.id
    WHERE d.id = target_document_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION '검증 대상 Document, Version, Job 관계를 찾지 못했습니다.';
    END IF;
    IF document_status <> 'INDEXED'
        OR version_status <> 'INDEXED'
        OR job_status <> 'INDEXED'
        OR current_version_id <> target_version_id THEN
        RAISE EXCEPTION '최종 INDEXED 상태 또는 current Version 불변식이 맞지 않습니다.';
    END IF;
    IF job_retry_count < 1 THEN
        RAISE EXCEPTION '장애 복구 Retry가 기록되지 않았습니다.';
    END IF;

    -- 2. 복구 실행이 같은 Version의 Chunk와 현재 Model Embedding을 한 Set만 남겼는지 검증한다.
    SELECT COUNT(*) INTO chunk_count
    FROM document_chunks
    WHERE document_version_id = target_version_id;

    SELECT COUNT(*) INTO embedding_count
    FROM embeddings
    WHERE document_version_id = target_version_id
      AND embedding_model_id = job_model_id
      AND status = 'ACTIVE';

    IF chunk_count = 0 THEN
        RAISE EXCEPTION '복구된 Version에 Chunk가 없습니다.';
    END IF;
    IF embedding_count <> chunk_count THEN
        RAISE EXCEPTION 'Chunk와 현재 Model Embedding 개수가 다릅니다.';
    END IF;

    SELECT COUNT(*) INTO duplicate_chunk_count
    FROM (
        SELECT chunk_index
        FROM document_chunks
        WHERE document_version_id = target_version_id
        GROUP BY chunk_index
        HAVING COUNT(*) > 1
    ) duplicate_chunks;

    SELECT COUNT(*) INTO duplicate_embedding_count
    FROM (
        SELECT chunk_id, embedding_model_id
        FROM embeddings
        WHERE document_version_id = target_version_id
        GROUP BY chunk_id, embedding_model_id
        HAVING COUNT(*) > 1
    ) duplicate_embeddings;

    IF duplicate_chunk_count <> 0 OR duplicate_embedding_count <> 0 THEN
        RAISE EXCEPTION '복구 뒤 중복 Chunk 또는 Embedding이 남았습니다.';
    END IF;

    -- 3. Outbox 고유 Key는 전체 Queue에서 중복이 없어야 하며 기존 Unique 제약을 실행 결과로 재확인한다.
    SELECT COUNT(*) INTO duplicate_outbox_count
    FROM (
        SELECT idempotency_key
        FROM sync_outbox_events
        GROUP BY idempotency_key
        HAVING COUNT(*) > 1
    ) duplicate_outbox_events;

    IF duplicate_outbox_count <> 0 THEN
        RAISE EXCEPTION '중복 Sync Outbox idempotency Key가 남았습니다.';
    END IF;
END
$docgrid_recovery$;

-- 4. 공개 결과 문서에 옮길 수 있는 상태와 건수만 출력하고 접속 정보·원문 Payload는 제외한다.
SELECT
    d.id AS document_id,
    d.status AS document_status,
    dv.id AS document_version_id,
    dv.status AS version_status,
    ej.id AS job_id,
    ej.status AS job_status,
    ej.retry_count,
    COUNT(DISTINCT dc.id) AS chunk_count,
    COUNT(DISTINCT e.id) AS embedding_count,
    COALESCE(soe.status, 'NO_SOURCE_EVENT') AS source_event_status
FROM documents d
JOIN document_versions dv
  ON dv.id = current_setting('docgrid.recovery.document_version_id')::BIGINT
 AND dv.document_id = d.id
JOIN embedding_jobs ej
  ON ej.id = current_setting('docgrid.recovery.job_id')::BIGINT
 AND ej.document_version_id = dv.id
LEFT JOIN document_chunks dc ON dc.document_version_id = dv.id
LEFT JOIN embeddings e
  ON e.chunk_id = dc.id
 AND e.document_version_id = dv.id
 AND e.embedding_model_id = ej.embedding_model_id
 AND e.status = 'ACTIVE'
LEFT JOIN sync_outbox_events soe ON soe.event_id = ej.source_event_id
WHERE d.id = current_setting('docgrid.recovery.document_id')::BIGINT
GROUP BY d.id, d.status, dv.id, dv.status, ej.id, ej.status, ej.retry_count, soe.status;
