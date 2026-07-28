package com.opensource.docgrid.domain.rag.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OllamaGenerateResponse(
    String response,
    boolean done,
    @JsonProperty("prompt_eval_count") Integer promptEvalCount,
    @JsonProperty("eval_count") Integer evalCount
) {
}
