package com.opensource.docgrid.domain.search.service.query;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * pgvector 코사인 거리 검색 서비스 (F-SEARCH-05).
 *
 * <p>permittedIds가 빈 목록이면 DB를 조회하지 않고 즉시 빈 목록을 반환한다.
 * queryVector는 '[v1,v2,...]' 형식 문자열로 변환해 네이티브 쿼리에 전달한다. 조회된 Top-K 후보는
 * 서버의 최소 유사도 정책을 통과한 경우에만 권한 검증, 저장과 RAG 문맥의 다음 단계로 전달한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchQueryService {

    private final VectorSearchRepository vectorSearchRepository;
    private final VectorSearchProperties vectorSearchProperties;

    public List<VectorSearchCandidate> search(
        float[] queryVector,
        Long modelId,
        List<Long> permittedIds,
        int topK
    ) {
        if (permittedIds.isEmpty()) {
            log.debug("[SEARCH] permittedIds empty — skip vector search");
            return List.of();
        }

        String vectorStr = toVectorString(queryVector);
        log.debug("[SEARCH] vector search modelId={} permittedCount={} topK={}", modelId, permittedIds.size(), topK);

        // 1. OpenSQL/pgvector의 기존 Top-K 순서와 실행계획을 유지한 채 후보를 조회한다.
        List<VectorSearchCandidate> candidates = vectorSearchRepository.findTopK(vectorStr, modelId, permittedIds, topK)
            .stream()
            .map(VectorSearchCandidate::from)
            .toList();

        // 2. 임계값 미달 후보가 검색 저장 결과나 RAG 근거로 전달되지 않도록 서버 경계에서 제거한다.
        BigDecimal minSimilarity = vectorSearchProperties.getMinSimilarity();
        List<VectorSearchCandidate> qualified = candidates.stream()
            .filter(candidate -> candidate.similarityScore().compareTo(minSimilarity) >= 0)
            .toList();

        log.info("[SEARCH] relevance filter modelId={} topK={} minSimilarity={} before={} after={}",
            modelId, topK, minSimilarity, candidates.size(), qualified.size());
        return qualified;
    }

    private String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
