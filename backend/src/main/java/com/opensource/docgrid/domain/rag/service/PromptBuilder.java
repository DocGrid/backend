package com.opensource.docgrid.domain.rag.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

/**
 * 검색 후보(chunk)들을 출처 라벨과 함께 LLM 프롬프트로 조립한다 (F-RAG-01).
 *
 * <p>search_results/document_chunks를 다시 조회하지 않고, SearchFacade가 이미 만든
 * {@link VectorSearchCandidate} 목록을 그대로 입력받는다. 인용 라벨([1], [2]...)은 이 목록의
 * 순서(검색 랭킹 순서)를 그대로 사용하며, 이 순서는 F-RAG-04의 citation_order와 동일하게 재사용된다.
 * 질문과 문맥이 직접 관련되지 않거나 충분한 근거가 없으면 고정 안내 문구만 반환하도록 LLM에 지시한다.
 * 모든 후보의 인용 라벨을 유지하되 청크별·전체 본문 예산을 적용해 LLM 입력 크기를 제한한다.
 *
 * <p>빈 후보 목록(NO_CONTEXT) 처리는 이 클래스의 책임이 아니다 — 호출 여부는 RagFacade가 판단한다.
 */
@Component
public class PromptBuilder {

    private static final int MAX_CHUNK_TEXT_CODE_POINTS = 800;
    private static final int MAX_CONTEXT_TEXT_CODE_POINTS = 6_000;

    private static final String INSTRUCTION =
        "다음은 참고 문서입니다. 먼저 질문과 문서가 직접 관련 있는지 판단하세요.\n"
            + "단순히 일부 단어가 겹친다는 이유만으로 관련 있다고 판단하지 마세요.\n"
            + "질문에 답할 충분한 근거가 없거나 문서가 무관하면 \"관련 문서를 찾지 못했습니다.\"라고만 답하세요.\n"
            + "관련 근거가 있을 때만 이 내용으로 답변하고, 문서에 없는 내용은 일반 지식이나 추측으로 보완하지 마세요.\n\n";

    public String build(String queryText, List<VectorSearchCandidate> candidates) {
        StringBuilder sb = new StringBuilder(INSTRUCTION);
        // 1. 후보가 많을수록 각 청크의 몫을 줄여 전체 본문 예산을 지킨다.
        int chunkTextLimit = chunkTextLimit(candidates.size());
        // 2. 본문은 줄여도 모든 후보의 인용 라벨과 순서는 유지한다.
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate candidate = candidates.get(i);
            sb.append(citationLine(i + 1, candidate, chunkTextLimit)).append('\n');
        }
        sb.append("\n질문: ").append(queryText);
        return sb.toString();
    }

    private int chunkTextLimit(int candidateCount) {
        if (candidateCount == 0) {
            return MAX_CHUNK_TEXT_CODE_POINTS;
        }
        int sharedLimit = Math.max(1, MAX_CONTEXT_TEXT_CODE_POINTS / candidateCount);
        return Math.min(MAX_CHUNK_TEXT_CODE_POINTS, sharedLimit);
    }

    private String citationLine(int order, VectorSearchCandidate candidate, int chunkTextLimit) {
        String pageSuffix = candidate.pageNo() != null ? " p." + candidate.pageNo() : "";
        String chunkText = truncate(candidate.chunkText(), chunkTextLimit);
        return "[%d] %s%s: \"%s\"".formatted(order, candidate.documentTitle(), pageSuffix, chunkText);
    }

    private String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }

        // 말줄임표까지 본문 예산에 포함해 전체 프롬프트 상한을 넘지 않도록 한다.
        int endIndex = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, endIndex) + "…";
    }
}
