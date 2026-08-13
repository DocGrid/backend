-- BAAI/bge-m3 임베딩 모델 seed (HUGGINGFACE, 1024차원, COSINE)
-- 기존 active+searchable 모델을 비활성화한 뒤 bge-m3를 활성 모델로 등록한다.
-- ON CONFLICT: provider+model_name+model_version unique 제약 기준으로 upsert.
UPDATE embedding_models
SET is_active = FALSE, is_searchable = FALSE
WHERE is_active = TRUE AND is_searchable = TRUE
  AND model_name != 'BAAI/bge-m3';

INSERT INTO embedding_models (
    provider, model_name, model_version, dimension,
    distance_metric, is_active, is_searchable, vector_storage_strategy,
    created_at, updated_at
) VALUES (
    'HUGGINGFACE', 'BAAI/bge-m3', '1.0', 1024,
    'COSINE', TRUE, TRUE, 'SINGLE_DIMENSION',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON CONFLICT (provider, model_name, model_version)
DO UPDATE SET
    is_active             = TRUE,
    is_searchable         = TRUE,
    dimension             = EXCLUDED.dimension,
    distance_metric       = EXCLUDED.distance_metric,
    vector_storage_strategy = EXCLUDED.vector_storage_strategy,
    updated_at            = CURRENT_TIMESTAMP;
