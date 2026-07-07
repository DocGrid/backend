package com.opensource.docgrid.domain.embedding.entity;

import com.opensource.docgrid.domain.embedding.enums.DistanceMetric;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;
import com.opensource.docgrid.domain.embedding.enums.VectorStorageStrategy;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 임베딩 모델 테이블.
 *
 * <p>역할: 어떤 임베딩 모델(provider/modelName/version)로 chunk를 벡터화했는지 추적한다.
 * 이유: 검색 시 query embedding과 document embedding은 반드시 같은 모델로 생성되어야 유의미하게 비교할 수 있다.
 * 관계: embeddings.embedding_model_id, embedding_jobs.embedding_model_id, search_queries.query_embedding_model_id가
 * 이 테이블을 참조한다.
 * unique 제약: (provider, model_name, model_version) 조합은 유일해야 한다.
 * index: is_active, is_searchable.
 *
 * <p>주의사항: 1단계 MVP는 active이면서 searchable인 모델을 단 1개만 사용하는 것을 전제로 한다.
 * TODO: 이를 애플리케이션 레벨 검증 또는 DB partial unique index로 보강해 "active+searchable 모델은 항상 1개"임을
 * 강제할 필요가 있다. dimension은 모델별로 고정된 값이며 embeddings.dimension과 반드시 일치해야 한다.
 * configJson은 Hibernate JSON 타입 매핑이 없어 TEXT로 임시 매핑했으며, 추후 OpenSQL JSON / Hibernate JSON
 * 매핑으로 교체가 필요하다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_models",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_embedding_models_provider_model_name_model_version",
                        columnNames = {"provider", "model_name", "model_version"}
                )
        },
        indexes = {
                @Index(name = "idx_embedding_models_is_active", columnList = "is_active"),
                @Index(name = "idx_embedding_models_is_searchable", columnList = "is_searchable")
        }
)
public class EmbeddingModel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmbeddingProvider provider;

    @Column(name = "model_name", nullable = false, length = 200)
    private String modelName;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    @Column(nullable = false)
    private int dimension;

    @Enumerated(EnumType.STRING)
    @Column(name = "distance_metric", nullable = false, length = 20)
    private DistanceMetric distanceMetric;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "is_searchable", nullable = false)
    private boolean isSearchable;

    @Enumerated(EnumType.STRING)
    @Column(name = "vector_storage_strategy", nullable = false, length = 30)
    private VectorStorageStrategy vectorStorageStrategy;

    // JSON 컬럼 임시 매핑(Hibernate JSON 타입 미설정) - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Builder
    public EmbeddingModel(EmbeddingProvider provider, String modelName, String modelVersion, int dimension,
                           DistanceMetric distanceMetric, boolean isActive, boolean isSearchable,
                           VectorStorageStrategy vectorStorageStrategy, String configJson) {
        this.provider = provider;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.dimension = dimension;
        this.distanceMetric = distanceMetric;
        this.isActive = isActive;
        this.isSearchable = isSearchable;
        this.vectorStorageStrategy = vectorStorageStrategy;
        this.configJson = configJson;
    }
}
