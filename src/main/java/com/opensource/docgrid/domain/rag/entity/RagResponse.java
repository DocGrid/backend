package com.opensource.docgrid.domain.rag.entity;

import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.global.common.entity.BaseEntity;

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

/**
 * RAG 최종 답변 테이블.
 *
 * <p>역할: 사용자가 실제로 보게 되는 최종 답변(LLM 응답)을 저장한다.
 * 이유: search_results가 "검색 후보"라면 rag_responses는 그 후보를 근거로 LLM이 생성한 "최종 답변"이다.
 * 이 둘을 분리해야 답변 오류 원인을 검색 단계/생성 단계로 나눠 분석할 수 있다.
 * 관계: query_id -> SearchQuery(1:1에 가까운 1:N, 하나의 query에 하나의 응답을 기본으로 함).
 * index: query_id, status, created_at.
 *
 * <p>주의사항: MVP에서는 실제 LLM 대신 Mock LLM 답변을 저장하더라도 테이블 구조/흐름은 동일하게 유지한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "rag_responses",
        indexes = {
                @Index(name = "idx_rag_responses_query_id", columnList = "query_id"),
                @Index(name = "idx_rag_responses_status", columnList = "status"),
                @Index(name = "idx_rag_responses_created_at", columnList = "created_at")
        }
)
public class RagResponse extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 답변이 근거로 하는 검색 요청
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_id", nullable = false)
    private SearchQuery query;

    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    @Column(name = "llm_model_name", length = 100)
    private String llmModelName;

    @Column(name = "prompt_text", columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "input_token_count")
    private Integer inputTokenCount;

    @Column(name = "output_token_count")
    private Integer outputTokenCount;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder
    public RagResponse(SearchQuery query, String answerText, String llmProvider, String llmModelName,
                        String promptText, Integer inputTokenCount, Integer outputTokenCount, Integer latencyMs,
                        ResultStatus status, String errorMessage) {
        this.query = query;
        this.answerText = answerText;
        this.llmProvider = llmProvider;
        this.llmModelName = llmModelName;
        this.promptText = promptText;
        this.inputTokenCount = inputTokenCount;
        this.outputTokenCount = outputTokenCount;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorMessage = errorMessage;
    }
}
