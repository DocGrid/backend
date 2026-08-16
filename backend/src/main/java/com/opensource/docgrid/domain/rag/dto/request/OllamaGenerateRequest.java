package com.opensource.docgrid.domain.rag.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OllamaGenerateRequest(
    String model,
    String prompt,
    boolean stream,
    // 채팅 템플릿(및 그에 딸린 tool-call PEG 파서)을 거치지 않고 프롬프트를 그대로 전달한다.
    // 템플릿을 타면 답변에 섞인 백틱(`ls` 등) 코드 표기를 모델이 tool-call 시도로 오인해
    // 생성이 done:false로 중간에 끊기는 문제가 있었다.
    boolean raw,
    @JsonProperty("keep_alive") String keepAlive,
    OllamaGenerateOptions options
) {
    public record OllamaGenerateOptions(
        @JsonProperty("num_predict") int numPredict,
        double temperature,
        @JsonProperty("top_p") double topP,
        @JsonProperty("repeat_penalty") double repeatPenalty,
        @JsonProperty("repeat_last_n") int repeatLastN
    ) {
    }
}
