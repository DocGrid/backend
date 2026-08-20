package com.opensource.docgrid.domain.search.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchResultCommandService;
import com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 전체 흐름을 조율하는 Facade (F-SEARCH-05/06/07).
 *
 * <pre>
 * 1. 질문 임베딩
 * 2. User/Collection 엔티티 조회 (search_queries FK)
 * 3. search_queries PROCESSING 저장
 * 4. 권한 pre-filter → 접근 가능한 document_id 목록 (빈 목록이면 skip)
 * 5. pgvector Top-K 후보 추출
 * 6. live check — 캐시 stale 방어
 * 7. search_results 저장
 * 8. search_queries SUCCESS + latency_ms 마감
 * </pre>
 *
 * <p>stale 캐시 방어 시나리오(권한 회수 직후 검색)는 6단계 live check 타이밍 로그로 추적 가능하다.
 */
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchFacade {

    private final QueryEmbeddingService queryEmbeddingService;
    private final SearchQueryCommandService searchQueryCommandService;
    private final AccessibleDocumentQueryService accessibleDocumentQueryService;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final PermissionQueryService permissionQueryService;
    private final SearchResultCommandService searchResultCommandService;
    private final UserRepository userRepository;
    private final CollectionRepository collectionRepository;

    public SearchOutcome search(Long userId, SearchRequest request) {
        long start = System.currentTimeMillis();

        // 1. 질문 임베딩
        EmbedResult embedResult = queryEmbeddingService.embed(request.queryText());

        // 2. User/Collection 엔티티 조회 (search_queries FK)
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        DocumentCollection collection = resolveCollection(request.collectionId());

        // 3. search_queries PROCESSING 저장
        SearchQuery searchQuery = searchQueryCommandService.createProcessing(
            user, collection, request.queryText(),
            embedResult.model(), embedResult.vector(), request.effectiveTopK()
        );

        try {
            // 4. 이 사용자가 볼 수 있는 문서 ID만 미리 추림
            List<Long> permittedIds = accessibleDocumentQueryService
                .findReadableDocumentIds(userId, request.collectionId());

            /*
             * 볼 수 있는 문서가 하나도 없으면 벡터 검색 자체를 생략한다.
             * 권한 문제로 결과가 없는 것은 실패가 아니라 정상 케이스이므로 markFailed가 아닌
             * markSuccess를 호출하고, 200 + 빈 결과로 응답한다.
             */
            if (permittedIds.isEmpty()) {
                log.info("[SEARCH] no accessible documents userId={}", userId);
                int latency = latencyMs(start);
                searchQueryCommandService.markSuccess(searchQuery, latency);
                return new SearchOutcome(SearchResponse.empty(searchQuery.getId()), List.of(), List.of());
            }

            // 5. pgvector Top-K 후보 추출 (F-SEARCH-05)
            List<VectorSearchCandidate> candidates = vectorSearchQueryService.search(
                embedResult.vector(), embedResult.model().getId(),
                permittedIds, request.effectiveTopK()
            );

            // 6. live check — 캐시 stale 방어 (F-SEARCH-06)
            long liveStart = System.currentTimeMillis();
            List<VectorSearchCandidate> verified = candidates.stream()
                .filter(c -> permissionQueryService.canReadDocument(userId, c.documentId()))
                .toList();
            log.info("[SEARCH] live check userId={} before={} after={} liveMs={}",
                userId, candidates.size(), verified.size(), latencyMs(liveStart));

            // 7. search_results 저장 (F-SEARCH-07)
            List<SearchResult> savedResults = searchResultCommandService.saveAll(searchQuery, verified);

            int latency = latencyMs(start);
            searchQueryCommandService.markSuccess(searchQuery, latency);
            log.info("[SEARCH] done queryId={} results={} latencyMs={}", searchQuery.getId(), verified.size(), latency);

            return new SearchOutcome(SearchResponse.of(searchQuery.getId(), verified), verified, savedResults);

        } catch (Exception e) {
            searchQueryCommandService.markFailed(searchQuery, e.getMessage());
            throw e;
        }
    }

    private DocumentCollection resolveCollection(Long collectionId) {
        if (collectionId == null) return null;
        return collectionRepository.findById(collectionId)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    }

    private int latencyMs(long fromMillis) {
        return (int) (System.currentTimeMillis() - fromMillis);
    }
}
