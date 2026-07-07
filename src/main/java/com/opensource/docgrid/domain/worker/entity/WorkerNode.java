package com.opensource.docgrid.domain.worker.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인덱싱 Worker 노드 테이블.
 *
 * <p>역할: 문서 파싱/청킹/임베딩을 수행하는 Worker 서버의 상태를 관리한다.
 * 이유: Worker 다중화(scale-out) 및 장애 복구를 지원하려면 각 Worker의 생사 여부를 추적해야 한다.
 * 관계: embedding_jobs.locked_by_worker_id, embedding_job_attempts.worker_node_id가 이 테이블을 참조한다.
 * index: status, last_heartbeat_at.
 *
 * <p>주의사항: heartbeat가 일정 시간 이상 갱신되지 않으면 DEAD로 처리하고, 해당 Worker가 lock한
 * embedding_jobs는 다른 Worker가 재처리할 수 있어야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "worker_nodes",
        indexes = {
                @Index(name = "idx_worker_nodes_status", columnList = "status"),
                @Index(name = "idx_worker_nodes_last_heartbeat_at", columnList = "last_heartbeat_at")
        }
)
public class WorkerNode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "worker_name", nullable = false, length = 200)
    private String workerName;

    @Column(name = "host_name", length = 255)
    private String hostName;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkerStatus status;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    @Builder
    public WorkerNode(String workerName, String hostName, String ipAddress, WorkerStatus status,
                       LocalDateTime lastHeartbeatAt, LocalDateTime startedAt) {
        this.workerName = workerName;
        this.hostName = hostName;
        this.ipAddress = ipAddress;
        this.status = status != null ? status : WorkerStatus.ACTIVE;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.startedAt = startedAt;
    }

    public void updateHeartbeat(LocalDateTime heartbeatAt) {
        this.lastHeartbeatAt = heartbeatAt;
    }

    public void markDead() {
        this.status = WorkerStatus.DEAD;
    }

    public void markStopped(LocalDateTime stoppedAt) {
        this.status = WorkerStatus.STOPPED;
        this.stoppedAt = stoppedAt;
    }
}
