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
 * conversation_id -> SearchConversation(질문과 답변을 묶는 사용자 대화방),
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

    // 질문·답변 기록을 화면에서 다시 열 수 있도록 모든 검색은 하나의 대화방에 속한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SearchConversation conversation;

    // 검색 범위를 특정 컬렉션으로 좁힌 경우에만 값이 있음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    private DocumentCollection collection;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    // 질의를 벡터화한 모델. 접수 직후와 임베딩 실패 시에는 null이며 성공 확정 때 저장한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_embedding_model_id")
    private EmbeddingModel queryEmbeddingModel;

    // 접수 직후와 임베딩 실패 시에는 null이며 성공 결과와 같은 Transaction에서 저장한다.
    @Type(VectorType.class)
    @Column(name = "query_vector", columnDefinition = "vector(1024)")
    private float[] queryVector;

    @Enumerated(EnumType.STRING)
    @Column(name = "search_type", nullable = false, length = 20)
    private SearchType searchType;

    @Column(name = "top_k", nullable = false)
    private int topK;

    // 현재 Schema가 TEXT이므로 문자열로 보존한다. JSONB 전환 시 Flyway와 Hibernate 매핑을 함께 바꿔야 한다.
    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 검색에 사용한 모델·Vector와 지연 시간을 채우고 실행을 성공으로 종결한다. */
    public void updateToSuccess(EmbeddingModel embeddingModel, float[] queryVector, int latencyMs) {
        this.queryEmbeddingModel = embeddingModel;
        this.queryVector = queryVector;
        this.status = ResultStatus.SUCCESS;
        this.latencyMs = latencyMs;
    }

    /**
     * 사용자의 대화·선택 컬렉션과 Query Vector를 포함한 검색 실행 원장을 생성한다.
     */
    @Builder
    public SearchQuery(User user, SearchConversation conversation, DocumentCollection collection, String queryText,
                        EmbeddingModel queryEmbeddingModel, float[] queryVector, SearchType searchType, int topK,
                        String filtersJson, Integer latencyMs, ResultStatus status, String errorMessage) {
        this.user = user;
        this.conversation = conversation;
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
