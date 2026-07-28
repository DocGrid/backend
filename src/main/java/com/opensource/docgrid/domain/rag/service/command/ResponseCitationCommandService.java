package com.opensource.docgrid.domain.rag.service.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.entity.ResponseCitation;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 응답 출처(citation) 저장 서비스 (F-RAG-04).
 *
 * <p>PromptBuilder가 프롬프트에 포함시킨 것과 동일한 candidates 순서를 citation_order/citation_label
 * 근거로 그대로 재사용한다. search_result_id는 이번 이슈에서 채우지 않는다(null) — SearchResultCommandService.saveAll()이
 * 저장된 SearchResult를 반환하지 않아 이 시점에는 알 수 없고, Issue 5에서 채운다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class ResponseCitationCommandService {

    private final ResponseCitationRepository responseCitationRepository;
    private final EntityManager entityManager;

    public void saveAll(RagResponse response, List<VectorSearchCandidate> candidates) {
        List<ResponseCitation> citations = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate c = candidates.get(i);
            citations.add(ResponseCitation.builder()
                .response(response)
                .chunk(entityManager.getReference(DocumentChunk.class, c.chunkId()))
                .citationOrder(i + 1)
                .citationLabel("[" + (i + 1) + "]")
                .quotedText(c.chunkText())
                .pageNo(c.pageNo())
                .relevanceScore(c.similarityScore())
                .build());
        }
        responseCitationRepository.saveAll(citations);
    }
}
