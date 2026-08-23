package com.opensource.docgrid.domain.rag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 주어진 상태(보통 PROCESSING)로 threshold 이전부터 남아있는 job들을 찾는다 —
     * RagJobTimeoutSweeper가 "얼마나 오래 대기 중인지"를 별도 컬럼 없이 {@code createdAt}
     * 기준으로 판단할 때 쓴다. {@link EntityGraph}로 {@code query}/{@code query.user}를 미리
     * fetch하는 이유는 {@code findFirstByStatusOrderByCreatedAtAsc}와 동일하다 — 스위퍼도
     * WebSocket 알림을 보내려면 트랜잭션 밖에서 {@code query.user.email}에 접근해야 한다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    List<RagResponse> findByStatusAndCreatedAtBefore(ResultStatus status, LocalDateTime threshold);

    /**
     * PROCESSING 상태인 job을 FAILED로 강제 종료한다. {@code WHERE ... AND status = PROCESSING}
     * 조건 덕분에, 이 UPDATE가 실행되는 순간 RagJobWorker가 이미 다른 트랜잭션에서 이 job을
     * SUCCESS/FAILED로 먼저 확정했다면 영향받은 행이 0건이 된다 — 이 저장소엔 {@code @Version}
     * 필드가 없어 엔티티를 그대로 불러와 save()하면 나중 쓰기가 그냥 이기는데, 그 대신 이 조건부
     * UPDATE로 "이미 끝난 job을 덮어쓰는" 경합을 막는다. 반환값(영향받은 행 수)으로 호출자가
     * 실제로 강제 종료가 일어났는지 판단한다.
     *
     * <p>{@code clearAutomatically}: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않고 DB에 직접
     * 실행되므로, 같은 트랜잭션에서 이 job 엔티티를 이미 로딩해둔 상태라면 그 캐시된 인스턴스가
     * 여전히 갱신 전 값을 들고 있다 — 이후 같은 트랜잭션에서 다시 조회해도 DB가 아니라 그 캐시를
     * 돌려줘 최신 상태를 못 본다. {@code clearAutomatically = true}로 UPDATE 직후 영속성
     * 컨텍스트를 비워 이 문제를 막는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED, "
        + "r.answerText = :answerText, r.errorMessage = :errorMessage "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING")
    int forceFailIfProcessing(@Param("id") Long id, @Param("answerText") String answerText,
                               @Param("errorMessage") String errorMessage);
}
