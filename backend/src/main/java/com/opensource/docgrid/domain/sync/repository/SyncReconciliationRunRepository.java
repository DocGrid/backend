package com.opensource.docgrid.domain.sync.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.sync.entity.SyncReconciliationRun;

/**
 * Reconciliation Batch 실행 이력을 영속화한다.
 */
public interface SyncReconciliationRunRepository extends JpaRepository<SyncReconciliationRun, Long> {

    Optional<SyncReconciliationRun> findByRunId(UUID runId);
}
