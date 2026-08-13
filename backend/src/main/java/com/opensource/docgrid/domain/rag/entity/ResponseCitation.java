package com.opensource.docgrid.domain.rag.entity;

import java.math.BigDecimal;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.search.entity.SearchResult;
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
 * 응답 출처(citation) 테이블.
 *
 * <p>역할: rag_responses의 답변이 어떤 document_chunk를 근거로 생성되었는지 저장한다.
 * 이유: 출처 기반 RAG의 핵심 테이블로, 사용자가 답변을 신뢰할 수 있도록 근거 chunk를 명시적으로 노출한다.
 * 관계: response_id -> RagResponse, chunk_id -> DocumentChunk, search_result_id -> SearchResult(해당 chunk가
 * 검색 후보였을 때의 결과 레코드).
 * unique 제약: 같은 응답 내에서 같은 chunk가 중복 인용되는 것을 금지한다.
 * index: response_id, chunk_id, search_result_id.
 *
 * <p>주의사항: citation_label은 "[1]", "[2]"처럼 사용자 화면에 노출되는 표시용 라벨이며, citation_order로
 * 노출 순서를 관리한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "response_citations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_response_citations_response_id_chunk_id", columnNames = {"response_id", "chunk_id"})
        },
        indexes = {
                @Index(name = "idx_response_citations_response_id", columnList = "response_id"),
                @Index(name = "idx_response_citations_chunk_id", columnList = "chunk_id"),
                @Index(name = "idx_response_citations_search_result_id", columnList = "search_result_id")
        }
)
public class ResponseCitation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 출처가 속한 최종 답변
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "response_id", nullable = false)
    private RagResponse response;

    // 근거가 된 chunk
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", nullable = false)
    private DocumentChunk chunk;

    // 해당 chunk가 검색 후보였을 때의 결과 레코드(연결 가능하면 참조)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_result_id")
    private SearchResult searchResult;

    // 노출 순서(정렬용 숫자). 1, 2, 3...
    @Column(name = "citation_order", nullable = false)
    private int citationOrder;

    // 사용자에게 노출되는 표시용 라벨(문자열). 예: "[1]", "[2]" — citationOrder와 별도로 둬서 표기 스타일만 바뀌어도(예: "(주1)") 정렬 로직에 영향 없게 한다
    @Column(name = "citation_label", length = 20)
    private String citationLabel;

    // 실제로 인용된 원문 텍스트. 원본 chunk가 나중에 수정/삭제돼도 답변 당시 근거는 그대로 남긴다
    @Column(name = "quoted_text", columnDefinition = "TEXT")
    private String quotedText;

    // 원본 문서에서 몇 페이지였는지. 페이지 개념이 없는 포맷은 null
    @Column(name = "page_no")
    private Integer pageNo;

    // 검색 시점의 관련도 점수(코사인 유사도). "왜 이 chunk가 뽑혔는지"를 정량적으로 같이 남긴다
    @Column(name = "relevance_score")
    private BigDecimal relevanceScore;

    @Builder
    public ResponseCitation(RagResponse response, DocumentChunk chunk, SearchResult searchResult, int citationOrder,
                             String citationLabel, String quotedText, Integer pageNo, BigDecimal relevanceScore) {
        this.response = response;
        this.chunk = chunk;
        this.searchResult = searchResult;
        this.citationOrder = citationOrder;
        this.citationLabel = citationLabel;
        this.quotedText = quotedText;
        this.pageNo = pageNo;
        this.relevanceScore = relevanceScore;
    }
}
