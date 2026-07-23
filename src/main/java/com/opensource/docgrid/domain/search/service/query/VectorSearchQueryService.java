package com.opensource.docgrid.domain.search.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * pgvector 코사인 거리 검색 서비스 (F-SEARCH-05).
 *
 * <p>permittedIds가 빈 목록이면 DB를 조회하지 않고 즉시 빈 목록을 반환한다.
 * queryVector는 '[v1,v2,...]' 형식 문자열로 변환해 네이티브 쿼리에 전달한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchQueryService {

    private final VectorSearchRepository vectorSearchRepository;

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

        return vectorSearchRepository.findTopK(vectorStr, modelId, permittedIds, topK)
            .stream()
            .map(VectorSearchCandidate::from)
            .toList();
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
