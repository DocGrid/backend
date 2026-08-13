package com.opensource.docgrid.domain.search.entity;

import java.math.BigDecimal;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 검색 결과(후보) 테이블.
 *
 * <p>역할: 하나의 검색 요청에 대해 반환된 검색 후보(chunk)들을 저장한다. LLM이 생성한 최종 답변이 아니라
 * "검색 후보" 목록임에 유의해야 한다.
 * 이유: RAG 답변에 오류가 있을 때, 검색 후보 자체가 잘못된 것인지(search_results 문제) 아니면
 * 답변 생성 단계의 문제인지(rag_responses 문제)를 구분해서 분석하기 위해 필요하다.
 * 관계: query_id -> SearchQuery, chunk_id -> DocumentChunk, embedding_id -> Embedding.
 * unique 제약: 같은 query 내에서 같은 chunk가 중복 저장되는 것을 금지한다.
 * index: query_id, chunk_id, embedding_id, rank_no.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "search_results",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_search_results_query_id_chunk_id", columnNames = {"query_id", "chunk_id"})
        },
        indexes = {
                @Index(name = "idx_search_results_query_id", columnList = "query_id"),
                @Index(name = "idx_search_results_chunk_id", columnList = "chunk_id"),
                @Index(name = "idx_search_results_embedding_id", columnList = "embedding_id"),
                @Index(name = "idx_search_results_rank_no", columnList = "rank_no")
        }
)
public class SearchResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 결과가 속한 검색 요청
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_id", nullable = false)
    private SearchQuery query;

    // 검색된 chunk
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", nullable = false)
    private DocumentChunk chunk;

    // 매칭에 사용된 임베딩
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_id")
    private Embedding embedding;

    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "similarity_score", nullable = false)
    private BigDecimal similarityScore;

    @Column(name = "keyword_score")
    private BigDecimal keywordScore;

    @Column(name = "final_score", nullable = false)
    private BigDecimal finalScore;

    @Column(name = "matched_text", columnDefinition = "TEXT")
    private String matchedText;

    @Builder
    public SearchResult(SearchQuery query, DocumentChunk chunk, Embedding embedding, int rankNo,
                         BigDecimal similarityScore, BigDecimal keywordScore, BigDecimal finalScore,
                         String matchedText) {
        this.query = query;
        this.chunk = chunk;
        this.embedding = embedding;
        this.rankNo = rankNo;
        this.similarityScore = similarityScore;
        this.keywordScore = keywordScore;
        this.finalScore = finalScore;
        this.matchedText = matchedText;
    }
}
