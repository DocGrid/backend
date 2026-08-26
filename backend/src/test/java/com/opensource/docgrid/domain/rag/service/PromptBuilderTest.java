package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

@DisplayName("PromptBuilder 단위 테스트")
class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    @DisplayName("정상 케이스: 후보 순서대로 [1], [2] 라벨이 부여되고 문서 제목/페이지/청크 텍스트가 포함된다")
    void build_withCandidates_appendsLabeledCitations() {
        // Given
        VectorSearchCandidate first = new VectorSearchCandidate(
            1L, 10L, 100L, "연차는 입사 1년 기준 15일이 부여됩니다.", 12, "인사규정.pdf", new BigDecimal("0.9")
        );
        VectorSearchCandidate second = new VectorSearchCandidate(
            2L, 20L, 200L, "연차 사용 시 부서장에게 사전 통보가 권장됩니다.", 3, "복지정책.pdf", new BigDecimal("0.8")
        );

        // When
        String prompt = promptBuilder.build("연차 규정 알려줘", List.of(first, second));

        // Then
        assertThat(prompt)
            .contains("[1] 인사규정.pdf p.12: \"연차는 입사 1년 기준 15일이 부여됩니다.\"")
            .contains("[2] 복지정책.pdf p.3: \"연차 사용 시 부서장에게 사전 통보가 권장됩니다.\"")
            .contains("질문: 연차 규정 알려줘");
    }

    @Test
    @DisplayName("페이지 번호가 없는 후보는 p. 표기가 생략된다")
    void build_withNullPageNo_omitsPageSuffix() {
        // Given
        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "페이지 개념이 없는 문서입니다.", null, "FAQ.txt", new BigDecimal("0.7")
        );

        // When
        String prompt = promptBuilder.build("질문", List.of(candidate));

        // Then
        assertThat(prompt).contains("[1] FAQ.txt: \"페이지 개념이 없는 문서입니다.\"");
    }

    @Test
    @DisplayName("무관한 문맥 거절 및 환각 방지 지시문과 질문이 항상 포함된다")
    void build_alwaysIncludesInstructionAndQuestion() {
        // When
        String prompt = promptBuilder.build("질문 내용", List.of());

        // Then
        assertThat(prompt)
            .contains("문서 내용이 질문 주제와 실제로 관련 있는지 판단하세요")
            .contains("단순히 일부 단어가 겹친다는 이유만으로 관련 있다고 판단하지 마세요")
            .contains("관련 문서를 찾지 못했습니다.\"라고만 답하세요")
            .contains("관련된 항목을 빠짐없이 구체적으로 정리해서 답변하세요")
            .contains("문서가 무엇에 대한 내용인지 3~4문장 이내로 간결하게 설명하세요")
            .contains("문서에 없는 내용은 일반 지식이나 추측으로 보완하지 마세요")
            .contains("답변은 반드시 한국어로만 작성하세요")
            .contains("질문: 질문 내용")
            .contains("번역하거나 반복해서 덧붙이지 말고 그대로 끝내세요");
    }

    @Test
    @DisplayName("긴 청크는 말줄임표를 포함해 800자로 제한한다")
    void build_longChunk_limitsChunkTextLength() {
        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "가".repeat(1_200), 1, "문서.pdf", new BigDecimal("0.9")
        );

        String prompt = promptBuilder.build("질문", List.of(candidate));

        assertThat(prompt)
            .contains("\"" + "가".repeat(799) + "…\"")
            .doesNotContain("가".repeat(800));
    }

    @Test
    @DisplayName("후보가 많아도 모든 라벨을 유지하며 전체 청크 본문을 3200자로 제한한다")
    void build_manyCandidates_sharesContextBudgetAndKeepsLabels() {
        List<VectorSearchCandidate> candidates = java.util.stream.LongStream.rangeClosed(1, 20)
            .mapToObj(id -> new VectorSearchCandidate(
                id, id, id, "가".repeat(1_000), 1, "문서 " + id, new BigDecimal("0.9")
            ))
            .toList();

        String prompt = promptBuilder.build("질문", candidates);

        long contextCodePoints = prompt.lines()
            .filter(line -> line.startsWith("["))
            .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
            .mapToLong(text -> text.codePointCount(0, text.length()))
            .sum();
        assertThat(prompt).contains("[1]", "[20]");
        assertThat(contextCodePoints).isEqualTo(3_200L);
        assertThat(prompt.codePoints().filter(codePoint -> codePoint == '…').count()).isEqualTo(20L);
    }

    @Test
    @DisplayName("후속 질문은 최근 대화를 질문 해석용 문맥으로 포함하되 문서 근거로 취급하지 않는다")
    void build_withConversationContext_marksHistoryAsInterpretationOnly() {
        ConversationContext context = new ConversationContext("휴가 규정을 요약해줘", "연차는 15일입니다.");

        String prompt = promptBuilder.build("그중 신청 절차는?", List.of(), List.of(context));

        assertThat(prompt)
            .contains("이전 대화는 현재 질문의 지시어와 맥락을 해석할 때만 참고하세요")
            .contains("이전 답변 자체를 문서 근거로 인용하지 마세요")
            .contains("[이전 대화 1] 사용자: 휴가 규정을 요약해줘")
            .contains("AI: 연차는 15일입니다.")
            .contains("질문: 그중 신청 절차는?");
    }
}
