package com.opensource.docgrid.domain.search.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SearchRequest(
    @NotBlank String queryText,
    @Min(1) @Max(20) Integer topK,
    @Positive Long collectionId
) {
    private static final int DEFAULT_TOP_K = 5;

    public int effectiveTopK() {
        return topK != null ? topK : DEFAULT_TOP_K;
    }
}
