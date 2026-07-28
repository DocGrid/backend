package com.opensource.docgrid.domain.rag.dto;

public record OllamaGenerateResult(
    String answerText,          // Ollama가 생성한 답변 문장 (Ollama 응답의 response 필드)
    Integer inputTokenCount,    // 프롬프트(지시문+출처+질문)가 소비한 토큰 수 (Ollama 응답의 prompt_eval_count)
    Integer outputTokenCount,   // 생성된 답변이 소비한 토큰 수 (Ollama 응답의 eval_count)
    int latencyMs               // 호출 시작~응답 수신까지 네트워크 왕복 포함 체감 시간(ms). Ollama의 total_duration(순수 연산 시간, 나노초)과는 다름
) {
}
