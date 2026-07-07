package com.opensource.docgrid.domain.worker.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 임베딩 작업 시도 이력 테이블.
 *
 * <p>역할: embedding_jobs 한 건에 대한 과거 처리 시도(attempt) 이력을 남긴다.
 * 이유: embedding_jobs는 "현재 상태"만 나타내므로, 몇 번째 시도에서 어떤 Worker가 왜 실패했는지를
 * 추적하려면 별도의 이력 테이블이 필요하다.
 * 관계: embedding_job_id -> EmbeddingJob, worker_node_id -> WorkerNode(시도를 수행한 Worker).
 * unique 제약: 같은 job 내에서 attempt_no가 중복되지 않아야 한다.
 * index: worker_node_id, status.
 *
 * <p>주의사항: 이 테이블은 append-only에 가까운 이력 테이블이며, embedding_jobs.retry_count와 함께
 * Worker 장애 복구/재시도 분석에 사용된다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_job_attempts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_embedding_job_attempts_embedding_job_id_attempt_no", columnNames = {"embedding_job_id", "attempt_no"})
        },
        indexes = {
                @Index(name = "idx_embedding_job_attempts_worker_node_id", columnList = "worker_node_id"),
                @Index(name = "idx_embedding_job_attempts_status", columnList = "status")
        }
)
public class EmbeddingJobAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 시도가 속한 임베딩 작업
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_job_id", nullable = false)
    private EmbeddingJob embeddingJob;

    // 이 시도를 수행한 Worker
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_node_id")
    private WorkerNode workerNode;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder
    public EmbeddingJobAttempt(EmbeddingJob embeddingJob, WorkerNode workerNode, int attemptNo, AttemptStatus status,
                                LocalDateTime startedAt, LocalDateTime endedAt, Long durationMs, String errorCode,
                                String errorMessage) {
        this.embeddingJob = embeddingJob;
        this.workerNode = workerNode;
        this.attemptNo = attemptNo;
        this.status = status != null ? status : AttemptStatus.STARTED;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public void markSuccess(LocalDateTime endedAt, Long durationMs) {
        this.status = AttemptStatus.SUCCESS;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
    }

    public void markFailed(LocalDateTime endedAt, Long durationMs, String errorCode, String errorMessage) {
        this.status = AttemptStatus.FAILED;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
