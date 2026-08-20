package com.opensource.docgrid.domain.rag.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama가 돌려주는 응답 한 줄(청크)의 모양 — {@code stream:true}라서 완성된 답변 전체가 아니라,
 * 생성 도중 여러 번 반복해서 오는 NDJSON(줄바꿈으로 구분된 JSON) 한 줄 한 줄을 나타낸다.
 *
 * <p>예를 들어 Ollama가 "안녕하세요"라는 5글자를 생성하면, 실제로는 이런 여러 줄이 순서대로 온다:
 * <pre>{@code
 * {"model":"qwen2.5:7b","response":"안","done":false}
 * {"model":"qwen2.5:7b","response":"녕","done":false}
 * {"model":"qwen2.5:7b","response":"하","done":false}
 * {"model":"qwen2.5:7b","response":"세","done":false}
 * {"model":"qwen2.5:7b","response":"요","done":false}
 * {"model":"qwen2.5:7b","response":"","done":true,"prompt_eval_count":676,"eval_count":216}
 * }</pre>
 * 이 record 하나가 위 여섯 줄 중 딱 한 줄을 나타낸다. {@code response}는 "이번 줄에서 새로 생긴
 * 글자 조각"일 뿐 전체 답변이 아니다 — 전체 답변은 {@code OllamaClient.readStream()}이 이 줄들을
 * 한 줄씩 읽으면서 {@code response} 값을 계속 이어붙여서(누적해서) 만든다. {@code done}은 이 줄이
 * 마지막 줄인지 표시하고, {@code promptEvalCount}(프롬프트가 소비한 토큰 수)와 {@code evalCount}
 * (생성된 답변이 소비한 토큰 수)는 위 예시처럼 보통 마지막 줄에만 실제 값이 들어있다(중간 줄은 비어있음).
 *
 * <p>Ollama는 실제로 {@code total_duration}, {@code context} 등 더 많은 필드를 돌려주지만,
 * 이 서비스가 실제로 쓰는 필드 5개만 선택해서 받는다.
 */
public record OllamaGenerateResponse(
    String model,
    String response,
    boolean done,
    @JsonProperty("prompt_eval_count") Integer promptEvalCount,
    @JsonProperty("eval_count") Integer evalCount
) {
}
