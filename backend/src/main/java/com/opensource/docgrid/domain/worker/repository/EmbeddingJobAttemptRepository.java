package com.opensource.docgrid.domain.worker.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;

/**
 * Embedding Job Attempt의 저장과 Claim Token 멱등 조회 및 Job별 최신 시도 조회를 담당하는 Repository.
 *
 * <p>Attempt 번호 계산과 생성은 Job 행을 먼저 잠근 Command Transaction에서만 수행하며, 이 Repository는
 * 별도의 전역 잠금을 만들지 않는다.
 */
public interface EmbeddingJobAttemptRepository extends JpaRepository<EmbeddingJobAttempt, Long> {

    /**
     * 지정 Job의 Attempt를 Worker와 함께 조회하고 호출자가 지정한 고정 정렬·Pagination을 적용한다.
     */
    @Query(
        value = """
            SELECT attempt
            FROM EmbeddingJobAttempt attempt
            LEFT JOIN FETCH attempt.workerNode worker
            WHERE attempt.embeddingJob.id = :jobId
            """,
        countQuery = """
            SELECT COUNT(attempt)
            FROM EmbeddingJobAttempt attempt
            WHERE attempt.embeddingJob.id = :jobId
            """
    )
    Page<EmbeddingJobAttempt> findAdminAttemptsByJobId(
        @Param("jobId") Long jobId,
        Pageable pageable
    );

    Optional<EmbeddingJobAttempt> findByEmbeddingJobIdAndClaimToken(Long embeddingJobId, String claimToken);

    Optional<EmbeddingJobAttempt> findTopByEmbeddingJobIdOrderByAttemptNoDesc(Long embeddingJobId);
}
