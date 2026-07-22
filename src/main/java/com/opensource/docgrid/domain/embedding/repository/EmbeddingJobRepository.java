package com.opensource.docgrid.domain.embedding.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

/**
 * Embedding Job Queue의 영속성과 Claim 후보 행 잠금을 담당하는 Repository.
 *
 * <p>일반 CRUD 외에 PostgreSQL의 {@code FOR UPDATE SKIP LOCKED}를 사용해 여러 Worker가 같은 PENDING
 * Job을 동시에 선택하지 않도록 한다.
 */
public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {

    /**
     * 우선순위 Queue 정책에 따라 다음 PENDING Job 한 건을 잠금 상태로 조회한다.
     *
     * <p>다른 Transaction이 잠근 행은 기다리지 않고 건너뛴다. 반환된 행 잠금은 호출한 Service의
     * Transaction이 끝날 때까지 유지돼야 하므로 반드시 Transaction 내부에서 호출한다.
     *
     * @return 잠금을 획득한 다음 PENDING Job, 처리 가능한 후보가 없으면 빈 값
     */
    @Query(value = """
        SELECT job.*
        FROM embedding_jobs job
        WHERE job.status = 'PENDING'
        ORDER BY job.priority DESC,
                 job.created_at ASC,
                 job.id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<EmbeddingJob> findNextPendingForUpdate();
}
