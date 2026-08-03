package com.opensource.docgrid.domain.worker.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

/**
 * Worker Node의 조회와 생명주기 상태를 조건부로 갱신하는 Repository.
 *
 * <p>Heartbeat와 정상 종료 외에 Lease 갱신 시 Worker fencing 상태를 직렬화할 수 있는 쓰기 잠금 조회와
 * Heartbeat가 만료된 Worker를 조건부로 DEAD 확정하는 갱신을 제공한다. Job과 함께 사용할 때는 호출자가
 * Embedding Job을 먼저 잠가야 한다.
 */
public interface WorkerNodeRepository extends JpaRepository<WorkerNode, Long> {

    List<WorkerNode> findAllByOrderByStartedAtDescIdDesc();

    /**
     * Lease 갱신이 Worker의 DEAD·STOPPED 확정과 경쟁하지 않도록 Worker 행을 쓰기 잠금으로 조회한다.
     *
     * <p>호출자는 Embedding Job을 먼저 잠가 전체 인덱싱 흐름의 Job → Worker 순서를 유지해야 한다.
    */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT worker FROM WorkerNode worker WHERE worker.id = :workerId")
    Optional<WorkerNode> findByIdForUpdate(@Param("workerId") Long workerId);

    /**
     * Heartbeat가 기준 시각을 넘긴 살아 있는 Worker만 DEAD로 확정한다.
     *
     * <p>상태 조건을 UPDATE에 포함해 늦은 Scheduler가 STOPPED 상태를 덮어쓰지 않게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE WorkerNode worker
        SET worker.status = :deadStatus,
            worker.updatedAt = :detectedAt
        WHERE worker.status IN :liveStatuses
          AND (worker.lastHeartbeatAt IS NULL OR worker.lastHeartbeatAt <= :heartbeatDeadline)
        """)
    int markDeadWorkers(
        @Param("detectedAt") LocalDateTime detectedAt,
        @Param("heartbeatDeadline") LocalDateTime heartbeatDeadline,
        @Param("deadStatus") WorkerStatus deadStatus,
        @Param("liveStatuses") Collection<WorkerStatus> liveStatuses
    );

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
          AND worker.status IN :stoppableStatuses
        """)
    int markStopped(
        @Param("workerId") Long workerId,
        @Param("instanceId") String instanceId,
        @Param("stoppedAt") LocalDateTime stoppedAt,
        @Param("stoppedStatus") WorkerStatus stoppedStatus,
        @Param("stoppableStatuses") Collection<WorkerStatus> stoppableStatuses
    );
}
