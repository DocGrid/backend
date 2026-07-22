package com.opensource.docgrid.domain.search.entity;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.common.entity.BaseEntity;
import com.opensource.docgrid.global.common.type.VectorType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

/**
 * 검색 요청 루트 테이블.
 *
 * <p>역할: 사용자의 검색 요청 한 건을 표현하는 루트 엔티티. search_results/rag_responses가 모두
 * 이 query를 기준으로 연결된다.
 * 이유: 검색 요청/응답의 전체 흐름을 하나의 query 단위로 추적하기 위함이다.
 * 관계: user_id -> User, collection_id -> DocumentCollection(nullable, 특정 컬렉션으로 범위를 좁힌 검색일 때),
 * query_embedding_model_id -> EmbeddingModel(질의 임베딩에 사용된 모델, document embedding과 동일해야 함).
 * index: user_id, collection_id, query_embedding_model_id, search_type, created_at.
 *
 * <p>주의사항: MVP는 SearchType.VECTOR 중심으로 동작하며 KEYWORD/HYBRID는 확장 여지로 남겨둔다.
 * queryVector는 VectorType(커스텀 Hibernate UserType)으로 float[]에 매핑한다.
 * filtersJson은 Hibernate JSON 매핑이 없어 TEXT로 임시 매핑했다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "search_queries",
        indexes = {
                @Index(name = "idx_search_queries_user_id", columnList = "user_id"),
                @Index(name = "idx_search_queries_collection_id", columnList = "collection_id"),
                @Index(name = "idx_search_queries_query_embedding_model_id", columnList = "query_embedding_model_id"),
                @Index(name = "idx_search_queries_search_type", columnList = "search_type"),
                @Index(name = "idx_search_queries_created_at", columnList = "created_at")
        }
)
public class SearchQuery extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 검색을 요청한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 검색 범위를 특정 컬렉션으로 좁힌 경우에만 값이 있음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    private DocumentCollection collection;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    // 질의를 벡터화할 때 사용한 임베딩 모델, document embedding 모델과 동일해야 함
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_embedding_model_id")
    private EmbeddingModel queryEmbeddingModel;

    @Type(VectorType.class)
    @Column(name = "query_vector", columnDefinition = "vector(1024)")
    private float[] queryVector;

    @Enumerated(EnumType.STRING)
    @Column(name = "search_type", nullable = false, length = 20)
    private SearchType searchType;

    @Column(name = "top_k", nullable = false)
    private int topK;

    // JSON 컬럼 임시 매핑(Hibernate JSON 타입 미설정) - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public void updateToSuccess(int latencyMs) {
        this.status = ResultStatus.SUCCESS;
        this.latencyMs = latencyMs;
    }

    public void updateToFailed(String errorMessage) {
        this.status = ResultStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    @Builder
    public SearchQuery(User user, DocumentCollection collection, String queryText,
                        EmbeddingModel queryEmbeddingModel, float[] queryVector, SearchType searchType, int topK,
                        String filtersJson, Integer latencyMs, ResultStatus status, String errorMessage) {
        this.user = user;
        this.collection = collection;
        this.queryText = queryText;
        this.queryEmbeddingModel = queryEmbeddingModel;
        this.queryVector = queryVector;
        this.searchType = searchType;
        this.topK = topK;
        this.filtersJson = filtersJson;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMessage = errorMessage;
    }
}
