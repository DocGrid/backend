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
import jakarta.persistence.UniqueConstraint;
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
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_worker_nodes_instance_id", columnNames = "instance_id")
        },
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

    @Column(name = "instance_id", nullable = false, length = 64)
    private String instanceId;

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

    /**
     * 애플리케이션 프로세스 하나를 식별하는 Worker와 최초 생존 시각을 생성한다.
     */
    @Builder
    public WorkerNode(String workerName, String instanceId, String hostName, String ipAddress, WorkerStatus status,
                       LocalDateTime lastHeartbeatAt, LocalDateTime startedAt) {
        this.workerName = workerName;
        this.instanceId = instanceId;
        this.hostName = hostName;
        this.ipAddress = ipAddress;
        this.status = status != null ? status : WorkerStatus.ACTIVE;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.startedAt = startedAt;
    }

    /**
     * ACTIVE 또는 IDLE Worker의 마지막 생존 확인 시각을 갱신한다.
     *
     * <p>종료·사망이 확정된 Worker를 늦은 Heartbeat가 되살리지 못하도록 종결 상태에서는 무시한다.
     */
    public void updateHeartbeat(LocalDateTime heartbeatAt) {
        if (!isLive()) {
            return;
        }
        this.lastHeartbeatAt = heartbeatAt;
    }

    /**
     * 생존 상태의 Worker를 Heartbeat 만료로 인한 DEAD 상태로 전환한다.
     */
    public void markDead() {
        if (!isLive()) {
            return;
        }
        this.status = WorkerStatus.DEAD;
    }

    /**
     * 정상 종료되는 생존 Worker를 STOPPED로 전환하고 종료 시각을 기록한다.
     */
    public void markStopped(LocalDateTime stoppedAt) {
        if (!isLive()) {
            return;
        }
        this.status = WorkerStatus.STOPPED;
        this.stoppedAt = stoppedAt;
    }

    /**
     * 저장 상태와 Heartbeat 임계 시각을 결합해 조회 시점의 실효 Worker 상태를 계산한다.
     *
     * <p>조회 전용 계산이므로 오래된 Heartbeat를 발견해도 Entity 상태 자체는 변경하지 않는다.
     */
    public WorkerStatus resolveEffectiveStatus(LocalDateTime heartbeatDeadline) {
        if (status == WorkerStatus.STOPPED || status == WorkerStatus.DEAD) {
            return status;
        }

        if (lastHeartbeatAt == null || !lastHeartbeatAt.isAfter(heartbeatDeadline)) {
            return WorkerStatus.DEAD;
        }

        return status;
    }

    /**
     * Heartbeat 또는 정상 종료 전이를 받을 수 있는 생존 상태인지 확인한다.
     */
    private boolean isLive() {
        return status == WorkerStatus.ACTIVE || status == WorkerStatus.IDLE;
    }
}
