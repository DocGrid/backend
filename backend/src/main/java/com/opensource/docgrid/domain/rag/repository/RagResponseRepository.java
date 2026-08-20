package com.opensource.docgrid.domain.rag.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

/**
 * RagResponse 엔티티에 대한 JPA Repository.
 */
public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {

    /**
     * 주어진 상태(보통 PROCESSING)인 것들 중 가장 오래 기다린 것 하나를 반환한다 — RagJobWorker가
     * 1초마다 폴링하며 이 메서드로 FIFO 큐를 구현한다. Worker가 1개뿐이라 별도 락/claim 없이도
     * 안전하다.
     *
     * <p>{@code query}/{@code query.user}를 {@link EntityGraph}로 미리 fetch한다 — Worker가
     * 이 메서드로 job을 꺼낸 트랜잭션이 끝난 뒤(WebSocket push 시점)에
     * {@code job.getQuery().getUser().getEmail()}에 접근해도 두 연관관계 모두 LAZY라서 자칫
     * {@code LazyInitializationException}이 날 수 있는데, 미리 로딩해두면 그 문제가 없다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    Optional<RagResponse> findFirstByStatusOrderByCreatedAtAsc(ResultStatus status);

    /** 특정 검색 요청(queryId)에 대한 RAG 답변을 찾는다. GET /search/{queryId} 재조회에 쓰인다. */
    Optional<RagResponse> findByQuery_Id(Long queryId);
}
