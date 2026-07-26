package com.opensource.docgrid.domain.worker.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;

/**
 * Embedding Job Attempt의 저장과 Claim Token 멱등 조회 및 Job별 최신 시도 조회를 담당하는 Repository.
 *
 * <p>Attempt 번호 계산과 생성은 Job 행을 먼저 잠근 Command Transaction에서만 수행하며, 이 Repository는
 * 별도의 전역 잠금을 만들지 않는다.
 */
public interface EmbeddingJobAttemptRepository extends JpaRepository<EmbeddingJobAttempt, Long> {

    Optional<EmbeddingJobAttempt> findByEmbeddingJobIdAndClaimToken(Long embeddingJobId, String claimToken);

    Optional<EmbeddingJobAttempt> findTopByEmbeddingJobIdOrderByAttemptNoDesc(Long embeddingJobId);
}
