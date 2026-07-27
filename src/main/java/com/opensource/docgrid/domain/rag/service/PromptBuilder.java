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
 *
 * <p>빈 후보 목록(NO_CONTEXT) 처리는 이 클래스의 책임이 아니다 — 호출 여부는 RagFacade가 판단한다.
 */
@Component
public class PromptBuilder {

    private static final String INSTRUCTION =
        "다음은 참고 문서입니다. 이 내용만을 근거로 답변하고,\n문서에 없는 내용은 추측하지 마세요.\n\n";

    public String build(String queryText, List<VectorSearchCandidate> candidates) {
        StringBuilder sb = new StringBuilder(INSTRUCTION);
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate candidate = candidates.get(i);
            sb.append(citationLine(i + 1, candidate)).append('\n');
        }
        sb.append("\n질문: ").append(queryText);
        return sb.toString();
    }

    private String citationLine(int order, VectorSearchCandidate candidate) {
        String pageSuffix = candidate.pageNo() != null ? " p." + candidate.pageNo() : "";
        return "[%d] %s%s: \"%s\"".formatted(order, candidate.documentTitle(), pageSuffix, candidate.chunkText());
    }
}
