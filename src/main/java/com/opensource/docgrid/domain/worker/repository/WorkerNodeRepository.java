package com.opensource.docgrid.domain.worker.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

public interface WorkerNodeRepository extends JpaRepository<WorkerNode, Long> {

    List<WorkerNode> findAllByOrderByStartedAtDescIdDesc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WorkerNode worker
        SET worker.lastHeartbeatAt = :heartbeatAt,
            worker.updatedAt = :heartbeatAt
        WHERE worker.id = :workerId
          AND worker.instanceId = :instanceId
          AND worker.status IN :heartbeatStatuses
        """)
    int updateHeartbeat(
        @Param("workerId") Long workerId,
        @Param("instanceId") String instanceId,
        @Param("heartbeatAt") LocalDateTime heartbeatAt,
        @Param("heartbeatStatuses") Collection<WorkerStatus> heartbeatStatuses
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WorkerNode worker
        SET worker.status = :stoppedStatus,
            worker.stoppedAt = :stoppedAt,
            worker.updatedAt = :stoppedAt
        WHERE worker.id = :workerId
          AND worker.instanceId = :instanceId
          AND worker.status <> :stoppedStatus
        """)
    int markStopped(
        @Param("workerId") Long workerId,
        @Param("instanceId") String instanceId,
        @Param("stoppedAt") LocalDateTime stoppedAt,
        @Param("stoppedStatus") WorkerStatus stoppedStatus
    );
}
