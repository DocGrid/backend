-- embedding_models: 임베딩 모델 테이블 (EmbeddingModel 엔티티 대응)
-- 주의: MVP는 active+searchable 모델을 단 1개만 사용하는 것을 전제로 한다.
-- TODO: "active+searchable 모델은 항상 1개"를 강제하는 partial unique index는 별도로 추가 검토 필요.
--   예: CREATE UNIQUE INDEX ux_embedding_models_single_searchable
--         ON embedding_models ((is_active AND is_searchable)) WHERE is_active AND is_searchable;
CREATE TABLE embedding_models (
    id BIGSERIAL PRIMARY KEY,

    provider VARCHAR(30) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    model_version VARCHAR(50) NOT NULL,
    dimension INT NOT NULL,
    distance_metric VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    is_searchable BOOLEAN NOT NULL DEFAULT FALSE,
    vector_storage_strategy VARCHAR(30) NOT NULL,

    -- JSON 컬럼 임시 매핑 - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    config_json TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_embedding_models_provider_model_name_model_version
        UNIQUE (provider, model_name, model_version)
);

CREATE INDEX idx_embedding_models_is_active ON embedding_models (is_active);
CREATE INDEX idx_embedding_models_is_searchable ON embedding_models (is_searchable);
