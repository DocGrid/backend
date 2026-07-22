package com.opensource.docgrid.domain.embedding.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;

public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {

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
