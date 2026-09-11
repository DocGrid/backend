package com.opensource.docgrid.domain.search.service.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * 검색 결과 저장 서비스 (F-SEARCH-07 일부).
 *
 * <p>live check를 통과한 후보 목록 저장과 SearchQuery SUCCESS 확정을 한 Transaction으로 묶는다.
 * EntityManager.getReference()로 Chunk·Embedding proxy를 만들어 불필요한 SELECT를 피한다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class SearchResultCommandService {

    private final SearchResultRepository searchResultRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final EntityManager entityManager;

    /**
     * 검색 원장을 다시 조회해 임베딩 정보와 결과를 저장하고 SUCCESS 상태까지 원자적으로 확정한다.
     */
    public List<SearchResult> saveAllAndComplete(
        Long queryId,
        EmbeddingModel embeddingModel,
        float[] queryVector,
        List<VectorSearchCandidate> candidates,
        int latencyMs
    ) {
        // 1. 이 Transaction이 직접 관리하는 PROCESSING 원장을 다시 조회한다.
        SearchQuery searchQuery = searchQueryRepository.findById(queryId)
            .orElseThrow(() -> new IllegalStateException("SearchQuery not found: " + queryId));
        if (searchQuery.getStatus() != ResultStatus.PROCESSING) {
            throw new IllegalStateException("SearchQuery is not PROCESSING: " + queryId);
        }

        // 2. 후보를 검색 순서대로 SearchResult로 변환해 같은 Transaction에 저장한다.
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
        List<SearchResult> savedResults = searchResultRepository.saveAll(results);

        // 3. 임베딩 정보와 SUCCESS를 함께 반영해 결과 저장과 상태 확정이 따로 커밋되지 않게 한다.
        searchQuery.updateToSuccess(embeddingModel, queryVector, latencyMs);
        return savedResults;
    }
}
