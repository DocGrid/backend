package com.opensource.docgrid.domain.embedding.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

/**
 * Embedding Job Queue의 영속성과 Claim 후보 행 잠금을 담당하는 Repository.
 *
 * <p>일반 CRUD 외에 PostgreSQL의 {@code FOR UPDATE SKIP LOCKED}로 Queue Claim 경쟁을 제어하고,
 * 단일 Job의 후속 상태·Attempt 변경에는 표준 JPA 쓰기 행 잠금을 제공한다.
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

    /**
     * 지정한 Job을 현재 Transaction이 끝날 때까지 쓰기 잠금 상태로 조회한다.
     *
     * <p>Attempt 생성·완료·복구 기능은 모두 Job을 먼저 잠그는 순서를 유지해야 한다. 그래야 Claim
     * 소유권 교체와 Job별 Attempt 번호 할당이 서로 겹치지 않는다.
     *
     * @param jobId 잠글 Embedding Job 식별자
     * @return 잠금을 획득한 Job, 존재하지 않으면 빈 값
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM EmbeddingJob job WHERE job.id = :jobId")
    Optional<EmbeddingJob> findByIdForUpdate(@Param("jobId") Long jobId);
}
