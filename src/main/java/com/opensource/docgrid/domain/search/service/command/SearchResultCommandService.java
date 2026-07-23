package com.opensource.docgrid.domain.search.service.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 검색 결과 저장 서비스 (F-SEARCH-07 일부).
 *
 * <p>live check를 통과한 후보 목록을 rank 순서로 search_results 테이블에 저장한다.
 * EntityManager.getReference()로 proxy를 만들어 불필요한 SELECT를 피한다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class SearchResultCommandService {

    private final SearchResultRepository searchResultRepository;
    private final EntityManager entityManager;

    public void saveAll(SearchQuery searchQuery, List<VectorSearchCandidate> candidates) {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate c = candidates.get(i);
            results.add(SearchResult.builder()
                .query(searchQuery)
                .chunk(entityManager.getReference(DocumentChunk.class, c.chunkId()))
                .embedding(entityManager.getReference(Embedding.class, c.embeddingId()))
                .rankNo(i + 1)
                .similarityScore(c.similarityScore())
                .finalScore(c.similarityScore())
                .matchedText(c.chunkText())
                .build());
        }
        searchResultRepository.saveAll(results);
    }
}
