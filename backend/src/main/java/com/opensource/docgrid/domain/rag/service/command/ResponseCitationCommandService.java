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
 * <p>{@code entityManager.getReference()}를 두 곳에서 쓰는데, 이유가 서로 다르다:
 * <ul>
 *   <li>{@code chunk} — {@code candidates}의 {@code chunkId}는 순수 DTO 값(엔티티 아님)이라
 *       detached 문제 자체가 없다. 여기서 쓰는 이유는 순수하게 성능 — 존재가 이미 확실한
 *       chunk의 FK만 연결하면 되므로 불필요한 SELECT를 피한다.</li>
 *   <li>{@code searchResult} — RagFacade가 선택한 검색 결과의 ID로 현재 영속성 컨텍스트의
 *       참조를 연결한다. 호출 측 엔티티의 영속 상태에 의존하지 않고 FK를 지정한다.</li>
 * </ul>
 */
@Transactional
@Service
@RequiredArgsConstructor
public class ResponseCitationCommandService {

    private final ResponseCitationRepository responseCitationRepository;
    private final EntityManager entityManager;

    /**
     * 답변 하나(response)와 그 답변이 근거로 쓴 검색 후보(candidates)·저장된 검색 결과
     * (searchResults)를 받아, 후보마다 ResponseCitation 엔티티를 만들어 일괄 저장한다.
     *
     * <p>RagFacade가 프롬프트와 동일한 상한으로 선택한 {@code searchResults}에서
     * {@code candidates}를 순서대로 변환해 전달한다. 두 목록은 길이와 순서가 같아야 하며,
     * 같은 인덱스의 후보 내용과 검색 결과 참조를 하나의 citation에 연결한다.
     */
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
