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
import com.opensource.docgrid.domain.search.entity.SearchResult;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 응답 출처(citation) 저장 서비스 (F-RAG-04).
 *
 * <p>PromptBuilder가 프롬프트에 포함시킨 것과 동일한 candidates 순서를 citation_order/citation_label
 * 근거로 그대로 재사용한다. search_result_id는 searchResults 인자로 함께 받아 연결한다.
 *
 * <p>searchResults는 SearchFacade의 트랜잭션이 이미 끝난(detached) 엔티티라, 그 객체를 그대로 FK에
 * 대입하지 않고 getId()만 꺼내 entityManager.getReference()로 이 트랜잭션의 프록시를 새로 만든다 —
 * chunk 필드를 연결할 때와 동일한 방식이다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class ResponseCitationCommandService {

    private final ResponseCitationRepository responseCitationRepository;
    private final EntityManager entityManager;

    // candidates와 searchResults는 SearchResultCommandService.saveAll()이 동일한 순서로 만든 것이므로 인덱스로 1:1 대응한다.
    public void saveAll(RagResponse response, List<VectorSearchCandidate> candidates, List<SearchResult> searchResults) {
        List<ResponseCitation> citations = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate c = candidates.get(i);
            citations.add(ResponseCitation.builder()
                .response(response)
                .chunk(entityManager.getReference(DocumentChunk.class, c.chunkId()))
                .searchResult(entityManager.getReference(SearchResult.class, searchResults.get(i).getId()))
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
