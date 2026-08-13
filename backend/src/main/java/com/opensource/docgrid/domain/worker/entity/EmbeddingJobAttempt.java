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
 * <p>역할: embedding_jobs 한 건에 대한 과거 처리 시도(attempt) 이력을 Claim 세대별로 남긴다.
 * 이유: embedding_jobs는 "현재 상태"만 나타내므로, 몇 번째 시도에서 어떤 Worker가 왜 실패했는지를
 * 추적하려면 별도의 이력 테이블이 필요하다.
 * 관계: embedding_job_id -> EmbeddingJob, worker_node_id -> WorkerNode(시도를 수행한 Worker).
 * unique 제약: 같은 Job 내 attempt_no와 같은 Job·Claim Token 조합이 각각 중복되지 않아야 한다.
 * index: worker_node_id, status.
 *
 * <p>주의사항: 신규 시작 경로는 Job, Worker, Claim Token, 양수 Attempt 번호, 시작 시각을 함께
 * 기록한다. Claim Token이 없는 기존 이력은 호환성을 위해 허용하며, 이 테이블은 append-only에 가까운
 * 실행 이력으로 사용한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_job_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_embedding_job_attempts_embedding_job_id_attempt_no",
                        columnNames = {"embedding_job_id", "attempt_no"}
                ),
                @UniqueConstraint(
                        name = "uk_embedding_job_attempts_embedding_job_id_claim_token",
                        columnNames = {"embedding_job_id", "claim_token"}
                )
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

    // 같은 Job이 재Claim됐을 때 이번 실행 시도를 현재 Claim 세대와 연결하는 멱등성 키다.
    @Column(name = "claim_token", length = 36)
    private String claimToken;

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
    public EmbeddingJobAttempt(EmbeddingJob embeddingJob, WorkerNode workerNode, int attemptNo, String claimToken,
                               AttemptStatus status, LocalDateTime startedAt, LocalDateTime endedAt, Long durationMs,
                               String errorCode, String errorMessage) {
        this.embeddingJob = embeddingJob;
        this.workerNode = workerNode;
        this.attemptNo = attemptNo;
        this.claimToken = claimToken;
        this.status = status != null ? status : AttemptStatus.STARTED;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 실행 중인 Attempt를 성공 상태로 종결한다.
     */
    public void markSuccess(LocalDateTime endedAt, Long durationMs) {
        // 한 Attempt가 두 번 종결되면 완료 재생의 기준 시각과 소요 시간이 변하므로 차단한다.
        if (status != AttemptStatus.STARTED) {
            throw new IllegalStateException("STARTED 상태의 Attempt만 SUCCESS로 전환할 수 있습니다.");
        }
        this.status = AttemptStatus.SUCCESS;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
    }

    public void markFailed(LocalDateTime endedAt, Long durationMs, String errorCode, String errorMessage) {
        // 한 Attempt의 최초 실패 내용이 멱등 재생 중 다른 값으로 덮이지 않도록 종결 상태를 차단한다.
        if (status != AttemptStatus.STARTED) {
            throw new IllegalStateException("STARTED 상태의 Attempt만 FAILED로 전환할 수 있습니다.");
        }
        this.status = AttemptStatus.FAILED;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
