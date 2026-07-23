package com.opensource.docgrid.domain.search.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

/**
 * 벡터 검색 후보 1건을 담는 내부 전달 객체.
 *
 * <p>similarityScore = max(0, 1 - cosineDistance) 로 변환해 저장한다.
 * pgvector `<=>` 코사인 거리는 이론상 [0, 2] 범위이므로 0으로 clamp해 음수를 방어한다.
 * live check(F-SEARCH-06) 통과 후 SearchResult 저장 및 응답 조립에 재사용한다.
 */
public record VectorSearchCandidate(
    Long embeddingId,
    Long chunkId,
    Long documentId,
    String chunkText,
    Integer pageNo,
    String documentTitle,
    BigDecimal similarityScore
) {
    public static VectorSearchCandidate from(VectorSearchRow row) {
        BigDecimal score = BigDecimal.ONE
            .subtract(BigDecimal.valueOf(row.getDistance()))
            .max(BigDecimal.ZERO)
            .setScale(6, RoundingMode.HALF_UP);
        return new VectorSearchCandidate(
            row.getEmbeddingId(),
            row.getChunkId(),
            row.getDocumentId(),
            row.getChunkText(),
            row.getPageNo(),
            row.getDocumentTitle(),
            score
        );
    }
}
