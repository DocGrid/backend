-- local/test에서 실제 BGE-M3와 같은 차원의 흐름을 검증하기 위한 기본 Mock 모델이다.
INSERT INTO embedding_models (
    provider,
    model_name,
    model_version,
    dimension,
    distance_metric,
    is_active,
    is_searchable,
    vector_storage_strategy,
    config_json
)
VALUES (
    'MOCK',
    'mock-bge-m3',
    'v1',
    1024,
    'COSINE',
    TRUE,
    TRUE,
    'SINGLE_DIMENSION',
    NULL
)
ON CONFLICT (provider, model_name, model_version)
DO UPDATE SET
    dimension = EXCLUDED.dimension,
    distance_metric = EXCLUDED.distance_metric,
    is_active = EXCLUDED.is_active,
    is_searchable = EXCLUDED.is_searchable,
    vector_storage_strategy = EXCLUDED.vector_storage_strategy,
    config_json = EXCLUDED.config_json,
    updated_at = CURRENT_TIMESTAMP;
