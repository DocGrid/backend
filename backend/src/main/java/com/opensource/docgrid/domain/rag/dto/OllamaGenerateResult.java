package com.opensource.docgrid.domain.rag.dto;

/**
 * {@code OllamaClient.generate()}가 스트림 청크들을 다 모아 최종적으로 돌려주는 결과.
 *
 * <p>Ollama 응답 형식({@link com.opensource.docgrid.domain.rag.dto.response.OllamaGenerateResponse})에
 * 종속된 그릇과, 이 서비스가 실제로 쓰는 값만 담은 그릇을 분리한 것이다 — 검색 도메인의
 * {@code EmbedResult}와 동일한 2단계 변환 패턴이다. Ollama 응답 형식이 바뀌거나 다른 LLM으로
 * 교체돼도, 이 record를 쓰는 쪽({@code RagResponseCommandService} 등)은 영향을 받지 않는다.
 */
public record OllamaGenerateResult(
    String model,                // 실제 응답을 생성한 모델명 (Ollama 응답의 model 필드)
    String answerText,          // Ollama가 생성한 답변 문장 (Ollama 응답의 response 필드)
    Integer inputTokenCount,    // 프롬프트(지시문+출처+질문)가 소비한 토큰 수 (Ollama 응답의 prompt_eval_count)
    Integer outputTokenCount,   // 생성된 답변이 소비한 토큰 수 (Ollama 응답의 eval_count)
    int latencyMs               // 호출 시작~응답 수신까지 네트워크 왕복 포함 체감 시간(ms). Ollama의 total_duration(순수 연산 시간, 나노초)과는 다름
) {
}
