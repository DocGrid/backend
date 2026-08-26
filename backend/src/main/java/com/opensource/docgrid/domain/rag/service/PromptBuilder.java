package com.opensource.docgrid.domain.rag.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

/**
 * 검색 후보(chunk)들을 출처 라벨과 함께 LLM 프롬프트로 조립한다 (F-RAG-01).
 *
 * <p>search_results/document_chunks를 다시 조회하지 않고, 호출 측이 넘겨준
 * {@link VectorSearchCandidate} 목록을 그대로 받아 조립만 한다 — 몇 개를 넘길지는 판단하지
 * 않는다. 실제로 {@code RagFacade.enqueue()}는 SearchFacade가 만든 원본 후보 전체가 아니라,
 * {@code MAX_PROMPT_CANDIDATES}(3)로 이미 잘라낸 상위 후보만 이 메서드에 넘긴다(#210) — 프롬프트가
 * 길어질수록 응답 시간이 예측 불가능해지는 문제 때문이다. 따라서 이 클래스가 매기는 인용 라벨
 * ([1], [2]...)은 "넘겨받은 목록 안에서의 순서"일 뿐이며, 화면에 노출되는 전체 citations 개수
 * (검색 결과 전체 기준)와 반드시 일치하지는 않는다 — LLM이 실제로 읽은 건 그중 상위 3개뿐일 수
 * 있다.
 *
 * <p>질문과 문맥이 직접 관련되지 않거나 충분한 근거가 없으면 고정 안내 문구만 반환하도록 LLM에
 * 지시한다. 모든 후보의 인용 라벨을 유지하되 청크별·전체 본문 예산을 적용해 LLM 입력 크기를
 * 제한한다.
 *
 * <p>빈 후보 목록(NO_CONTEXT) 처리는 이 클래스의 책임이 아니다 — 호출 여부는 RagFacade가 판단한다.
 */
@Component
public class PromptBuilder {

    /*
     * 프롬프트 길이 상한 2개. Ollama 추론 시간은 대부분 prefill(프롬프트를 읽는 시간, 문맥 토큰
     * 수에 비례)이 차지한다 — 프롬프트가 길수록 첫 글자가 나오기까지 오래 걸린다.
     *   MAX_CHUNK_TEXT_CODE_POINTS(800)     — 청크 하나가 최대 몇 글자까지 허용되는지.
     *   MAX_CONTEXT_TEXT_CODE_POINTS(3200)  — 청크들을 다 합친 전체 본문 합계 상한. 원래 6000
     *                                         이었다가 #210에서 절반으로 줄여, read-timeout
     *                                         안에서 생성(decode)에 쓸 시간을 더 확보했다.
     */
    private static final int MAX_CHUNK_TEXT_CODE_POINTS = 800;
    private static final int MAX_CONTEXT_TEXT_CODE_POINTS = 3_200;
    private static final int MAX_HISTORY_ITEM_CODE_POINTS = 600;

    /*
     * 매 질문마다 항상 앞에 붙는 고정 지시문. 4가지 규칙을 담고 있다:
     *   1) 단순 단어 겹침만으로 관련 있다고 판단하지 말 것 — 무관한 후보가 섞여 들어와도
     *      LLM이 억지로 답을 짜내지 않게 한다.
     *   2) 무관하면 "관련 문서를 찾지 못했습니다."라고만 답할 것 — 이 정확한 문구를 RagFacade가
     *      나중에 문자열로 찾아내 citations 노출 여부를 판단하는 근거가 된다.
     *   3) 구체적 키워드 질문(#210 이전엔 없던 분기) — 대상을 지정한 질문은 항목을 빠짐없이
     *      나열, 막연한 요약 요청은 3~4문장으로 간결하게. 원래 하나로 뭉뚱그려 있어서 구체적
     *      질문에도 알맹이 없는 개괄 답변만 나오는 문제가 있었다(#210).
     *   4) 반드시 한국어로만 답할 것 — 실전 QA에서 중국어가 섞여 나오는 문제를 겪고 추가(#210).
     *      build()가 프롬프트 맨 끝에도 이 지시를 한 번 더 반복하는데, recency 효과(모델이
     *      가장 최근에 본 지시를 더 잘 따르는 경향)를 노린 것이다.
     */
    private static final String INSTRUCTION =
        "다음은 검색으로 찾은 참고 문서입니다. 문서 내용이 질문 주제와 실제로 관련 있는지 판단하세요.\n"
            + "단순히 일부 단어가 겹친다는 이유만으로 관련 있다고 판단하지 마세요.\n"
            + "문서 주제 자체가 질문과 무관하면 \"관련 문서를 찾지 못했습니다.\"라고만 답하세요.\n"
            + "질문이 특정 키워드나 항목(예: 특정 명령어, 용어)을 지정해 그 내용을 찾아달라는 요청이면, "
            + "문서에서 해당 부분을 찾아 관련된 항목을 빠짐없이 구체적으로 정리해서 답변하세요. "
            + "이 경우 문서가 어떤 주제인지 개괄적으로만 설명하지 마세요.\n"
            + "질문이 특정 항목을 지정하지 않고 문서 전체를 요약하거나 소개해달라는 요청이면(예: \"문서 찾아줘\", "
            + "\"요약해줘\", \"소개해줘\"), 문서가 무엇에 대한 내용인지 3~4문장 이내로 간결하게 설명하세요.\n"
            + "문서에 없는 내용은 일반 지식이나 추측으로 보완하지 마세요.\n"
            + "질문이나 문서에 다른 언어가 섞여 있어도 답변은 반드시 한국어로만 작성하세요.\n\n";

    /**
     * 검색 후보 리스트와 질문을 받아 하나의 프롬프트 문자열로 조립한다.
     *
     * <p>고정 지시문({@link #INSTRUCTION}) → 후보마다 {@code [순번] 제목 p.페이지: "발췌문"} 한
     * 줄씩 → 질문 → 한국어 강제 재강조, 순서로 이어붙인다. 빈 리스트가 들어와도 예외 없이
     * 지시문+질문만 있는 프롬프트를 그대로 만든다 — 호출 여부(NO_CONTEXT 판단)는 RagFacade의
     * 책임이지 이 메서드의 책임이 아니다.
     */
    public String build(String queryText, List<VectorSearchCandidate> candidates) {
        return build(queryText, candidates, List.of());
    }

    /**
     * 최근 대화 문맥과 검색 근거를 함께 조립하되, 이전 답변은 질문 해석에만 사용하고
     * 문서 근거로 인용하지 않도록 경계를 명시한다.
     */
    public String build(
        String queryText,
        List<VectorSearchCandidate> candidates,
        List<ConversationContext> conversationContext
    ) {
        StringBuilder sb = new StringBuilder(INSTRUCTION);
        if (!conversationContext.isEmpty()) {
            sb.append("이전 대화는 현재 질문의 지시어와 맥락을 해석할 때만 참고하세요. ")
                .append("이전 답변 자체를 문서 근거로 인용하지 마세요.\n");
            for (int i = 0; i < conversationContext.size(); i++) {
                ConversationContext turn = conversationContext.get(i);
                sb.append("[이전 대화 ").append(i + 1).append("] 사용자: ")
                    .append(truncate(turn.queryText(), MAX_HISTORY_ITEM_CODE_POINTS))
                    .append("\nAI: ")
                    .append(truncate(turn.answerText(), MAX_HISTORY_ITEM_CODE_POINTS))
                    .append('\n');
            }
            sb.append('\n');
        }
        int chunkTextLimit = chunkTextLimit(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate candidate = candidates.get(i);
            sb.append(citationLine(i + 1, candidate, chunkTextLimit)).append('\n');
        }
        sb.append("\n질문: ").append(queryText);
        sb.append("\n\n(다시 한번 강조: 답변은 한국어로만 작성하세요. 답변을 마쳤으면 같은 내용을 다른 언어로 "
            + "번역하거나 반복해서 덧붙이지 말고 그대로 끝내세요.)");
        return sb.toString();
    }

    /**
     * 후보 개수에 따라 청크 하나당 허용 글자 수를 동적으로 나눈다 — 후보가 3개면
     * {@code 3200/3=1066}이지만 청크당 상한(800)이 더 작아 800으로 결정되고, 후보가 10개면
     * {@code 3200/10=320}으로 800보다 작아 320으로 결정된다. 이렇게 후보 수가 늘수록 청크당
     * 몫을 줄여, 후보가 몇 개든 전체 본문 합계가 {@link #MAX_CONTEXT_TEXT_CODE_POINTS}를
     * 넘지 않게 한다.
     */
    private int chunkTextLimit(int candidateCount) {
        if (candidateCount == 0) {
            return MAX_CHUNK_TEXT_CODE_POINTS;
        }
        int sharedLimit = Math.max(1, MAX_CONTEXT_TEXT_CODE_POINTS / candidateCount);
        return Math.min(MAX_CHUNK_TEXT_CODE_POINTS, sharedLimit);
    }

    /**
     * 후보 하나를 {@code [순번] 문서제목 p.페이지: "발췌문"} 한 줄로 변환한다. 페이지 개념이
     * 없는 문서 포맷은 {@code p.N} 부분을 통째로 생략한다.
     *
     * <p>{@code order}는 candidates 리스트에서의 인덱스+1일 뿐이다 — candidates는 이미
     * 유사도순+live check를 거친 최종 순서라 별도 재정렬이 필요 없고, 이 순서가 그대로
     * {@code ResponseCitationCommandService}가 매기는 {@code citation_order}와 일치해
     * "프롬프트의 [2]번 = DB의 citation_order=2"가 항상 맞아떨어진다.
     */
    private String citationLine(int order, VectorSearchCandidate candidate, int chunkTextLimit) {
        String pageSuffix = candidate.pageNo() != null ? " p." + candidate.pageNo() : "";
        String chunkText = truncate(candidate.chunkText(), chunkTextLimit);
        return "[%d] %s%s: \"%s\"".formatted(order, candidate.documentTitle(), pageSuffix, chunkText);
    }

    /**
     * 글자 수 상한을 넘으면 말줄임표(…)로 잘라낸다.
     *
     * <p>{@code text.length()}(자바 문자열 길이) 대신 {@code codePointCount()}를 쓴다 — 일부
     * 문자는 자바 내부적으로 2개의 char(surrogate pair)로 표현돼 length()로 세면 사람이 보는
     * 실제 글자 수보다 커질 수 있다. {@code maxCodePoints - 1}까지만 자르는 이유는 말줄임표
     * 자체도 한 글자로 쳐서, 잘라낸 텍스트 + 말줄임표의 총 글자 수가 상한을 넘지 않게 하기
     * 위함이다.
     */
    private String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }

        int endIndex = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, endIndex) + "…";
    }
}
