package com.opensource.docgrid.domain.sync.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;

/**
 * Sync 관리자 명령 감사 이력을 append-only로 저장한다.
 */
public interface SyncAdminActionRepository extends JpaRepository<SyncAdminAction, Long> {

    Optional<SyncAdminAction> findByActionId(UUID actionId);
}
