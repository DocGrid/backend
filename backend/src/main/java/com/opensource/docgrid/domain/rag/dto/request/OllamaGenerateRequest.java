package com.opensource.docgrid.domain.rag.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama {@code POST /api/generate}에 실제로 보내는 요청 본문 — 이 record 하나가 그대로 JSON으로
 * 직렬화돼 Ollama 서버로 나간다. 실제로 나가는 요청은 대략 이런 모양이다:
 * <pre>{@code
 * {
 *   "model": "qwen2.5:7b",
 *   "prompt": "다음은 참고 문서입니다...\n\n질문: 연차 규정 알려줘",
 *   "stream": true,
 *   "raw": true,
 *   "keep_alive": "30m",
 *   "options": {
 *     "num_predict": 400,
 *     "temperature": 0.3,
 *     "top_p": 0.8,
 *     "repeat_penalty": 1.1,
 *     "repeat_last_n": 256
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code model} — 어떤 모델에게 물어볼지(예: "qwen2.5:7b").</li>
 *   <li>{@code prompt} — {@code PromptBuilder}가 조립한 실제 질문 전문.</li>
 *   <li>{@code stream} — 항상 {@code true}로 고정한다. 완성된 응답을 한 번에 기다리는 대신
 *       청크 단위로 받으면서 애플리케이션 레벨 데드라인({@code ollama.generate-deadline})을
 *       감시하기 위함이다(#210). {@code stream:false}였던 원래 방식은 read-timeout을 넘기면
 *       그때까지 만들어진 답변까지 통째로 버려졌는데, {@code stream:true}로 바꾸면 "지금까지
 *       받은 조각"을 살릴 수 있다.</li>
 *   <li>{@code raw} — 항상 {@code true}로 고정한다. {@code false}(기본값)로 두면 이런 버그가
 *       있었다(#210): ①답변 텍스트 안에 백틱으로 감싼 코드(예: {@code `ls`})가 등장 → ②Ollama가
 *       이걸 "모델이 도구를 실행하려는 명령"이라고 잘못 해석 → ③그 오해 때문에 답변 생성을
 *       끝까지 안 하고 중간에 멈춰버림. {@code raw:true}는 이 오해 자체가 생기지 않게 막는다.</li>
 *   <li>{@code keepAlive} — 요청 사이 모델을 GPU 메모리에 상주시켜(예: "30m"), 매 요청마다
 *       재로딩(콜드스타트 9~11초)이 발생하지 않게 한다.</li>
 *   <li>{@code options} — 샘플링/생성 세부 옵션 5개를 {@link OllamaGenerateOptions}로 묶은 것.
 *       우리가 임의로 고른 구조가 아니라, Ollama가 요구하는 JSON 모양이 원래 {@code options}라는
 *       하위 객체 하나에 세부값을 몰아넣는 형태이기 때문이다.</li>
 * </ul>
 */
public record OllamaGenerateRequest(
    String model,
    String prompt,
    boolean stream,
    boolean raw,
    @JsonProperty("keep_alive") String keepAlive,
    OllamaGenerateOptions options
) {
    /**
     * Ollama {@code options} 필드에 대응하는 샘플링/생성 옵션. RAG는 문서 내용을 그대로 답하는
     * 용도라 창의성이 불필요해, 아래처럼 전부 "무난하고 안정적인 답변" 쪽으로 값을 조정했다(#210).
     *
     * <ul>
     *   <li>{@code numPredict} — 답변 최대 길이(토큰 수) 상한(400). 도달하면 답변이 중간에
     *       잘리고 OllamaClient가 잘림 안내 문구를 자동으로 붙인다.</li>
     *   <li>{@code temperature} — 단어를 고를 때 "얼마나 자유분방하게 고를지"(0에 가까울수록
     *       무난하고 뻔한 단어만 고름). 0.3으로 낮춤 — 값이 높으면 확률 낮은(드문) 단어도 종종
     *       뽑히는데, 그중에 한자/가나 같은 엉뚱한 문자가 섞여 나오는 걸 줄이려고 낮췄다.</li>
     *   <li>{@code topP} — temperature와 같은 목적(무작위성 억제)의 또 다른 다이얼. "확률 상위
     *       80%(0.8) 안의 단어만 후보로 삼는다"는 뜻 — temperature와 함께 낮춰 이중으로
     *       창의성을 억제한다.</li>
     *   <li>{@code repeatPenalty} — 같은 말을 반복하면 다시 뽑힐 확률을 낮추는 페널티 강도.
     *       1.0이면 반복 억제가 아예 없는 것(Ollama 기본값). 1.1로 올려서 모델이 답을 끝내고도
     *       "감사합니다…" 같은 잡담을 반복하며 토큰 상한까지 채우는 현상을 억제한다.</li>
     *   <li>{@code repeatLastN} — {@code repeatPenalty}가 "얼마나 최근까지"의 단어를 반복 검사
     *       대상으로 볼지. 기본값 64는 너무 짧아서 64토큰보다 긴 블록(예: 문단 하나)이 통째로
     *       반복되는 걸 못 잡아, 256으로 늘렸다.</li>
     * </ul>
     */
    public record OllamaGenerateOptions(
        @JsonProperty("num_predict") int numPredict,
        double temperature,
        @JsonProperty("top_p") double topP,
        @JsonProperty("repeat_penalty") double repeatPenalty,
        @JsonProperty("repeat_last_n") int repeatLastN
    ) {
    }
}
