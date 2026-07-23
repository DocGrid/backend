package com.opensource.docgrid.domain.search.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
    @NotBlank String queryText,
    @Min(1) @Max(20) Integer topK,
    Long collectionId
) {
    private static final int DEFAULT_TOP_K = 5;

    public int effectiveTopK() {
        return topK != null ? topK : DEFAULT_TOP_K;
    }
}
