package com.opensource.docgrid.domain.search.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class SearchQueryCommandService {

    private final SearchQueryRepository searchQueryRepository;

    /**
     * 질문 임베딩이 끝난 직후, 검색 요청 한 건을 PROCESSING 상태로 신규 저장한다.
     *
     * <p>searchType은 파라미터로 받지 않고 VECTOR로 고정한다 — 현재는 벡터 검색만 지원하며,
     * KEYWORD/HYBRID는 추후 확장 시 이 부분부터 파라미터화가 필요하다. 신규 엔티티라서
     * {@code save()}를 명시적으로 호출한다(더티체킹 대상이 아직 없으므로).
     */
    public SearchQuery createProcessing(
        User user,
        DocumentCollection collection,
        String queryText,
        EmbeddingModel model,
        float[] vector,
        int topK
    ) {
        SearchQuery searchQuery = SearchQuery.builder()
            .user(user)
            .collection(collection)
            .queryText(queryText)
            .queryEmbeddingModel(model)
            .queryVector(vector)
            .searchType(SearchType.VECTOR)
            .topK(topK)
            .status(ResultStatus.PROCESSING)
            .build();
        return searchQueryRepository.save(searchQuery);
    }

    /**
     * 검색이 끝까지 성공했을 때 상태를 SUCCESS로 확정한다.
     *
     * <p>searchQuery는 이미 영속 상태이므로 {@code save()} 없이 필드만 바꿔도 트랜잭션
     * 커밋 시점에 더티체킹으로 자동 UPDATE된다.
     */
    public void markSuccess(SearchQuery searchQuery, int latencyMs) {
        searchQuery.updateToSuccess(latencyMs);
    }

    /**
     * 검색 도중 실패했을 때 상태를 FAILED로 기록한다.
     *
     * <p>REQUIRES_NEW인 이유: {@code SearchFacade.search()} 전체가 하나의 트랜잭션인데,
     * 이 메서드가 그 트랜잭션 안에서 그대로 실행되면 뒤이은 {@code throw e}로 바깥 트랜잭션이
     * 롤백될 때 방금 기록한 FAILED 상태까지 함께 사라진다. 별도의 새 트랜잭션으로 즉시 커밋해
     * "실패해도 실패 기록은 남는다"는 원칙을 지킨다.
     *
     * <p><b>알려진 미해결 리스크</b>: {@code createProcessing()}으로 만든 row가 바깥 트랜잭션에서
     * 아직 커밋되지 않은 상태에서, 이 메서드가 별도 트랜잭션(별도 커넥션)으로 그 미확정 row를
     * UPDATE하려고 시도하는 구조다. DB 격리 수준과 락 상황에 따라 두 트랜잭션이 서로를 기다리는
     * 상황이 이론상 가능하며, 이 시나리오는 아직 통합 테스트로 검증된 적이 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(SearchQuery searchQuery, String errorMessage) {
        searchQuery.updateToFailed(errorMessage);
    }
}
