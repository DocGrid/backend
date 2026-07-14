ALTER TABLE embedding_models
    ADD CONSTRAINT ck_embedding_models_dimension_positive
        CHECK (dimension > 0);

-- 신규 작업과 검색에 동시에 사용할 모델은 최대 하나만 허용한다.
CREATE UNIQUE INDEX uk_embedding_models_one_active_searchable
    ON embedding_models ((1))
    WHERE is_active = TRUE
      AND is_searchable = TRUE;
