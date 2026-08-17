package com.opensource.docgrid.domain.rag.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {

    // Worker가 순서대로 하나씩 꺼내 처리한다 — Worker가 1개뿐이라 별도 락/claim 없이도 안전하다.
    // query/query.user를 미리 fetch해 Worker가 트랜잭션 밖(WebSocket push 시점)에서
    // job.getQuery().getUser().getEmail()에 접근해도 LazyInitializationException이 나지 않게 한다.
    @EntityGraph(attributePaths = {"query", "query.user"})
    Optional<RagResponse> findFirstByStatusOrderByCreatedAtAsc(ResultStatus status);

    Optional<RagResponse> findByQuery_Id(Long queryId);
}
