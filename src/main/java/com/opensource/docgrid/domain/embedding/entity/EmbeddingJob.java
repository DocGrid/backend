package com.opensource.docgrid.domain.embedding.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 임베딩 작업 큐 테이블.
 *
 * <p>역할: 문서 업로드 후 비동기로 수행되는 파싱/청킹/임베딩 인덱싱 작업을 큐잉한다.
 * 이유: 업로드 API는 이 테이블에 PENDING job만 생성하고 즉시 응답하며, 실제 무거운 처리는
 * Worker가 비동기로 수행한다(문서 업로드 -> documents/document_versions 생성 -> embedding_jobs로 비동기 인덱싱
 * -> Worker가 파싱/청킹/임베딩 -> embeddings 저장).
 * 관계: document_version_id -> DocumentVersion, embedding_model_id -> EmbeddingModel,
 * locked_by_worker_id -> WorkerNode(nullable, lock을 잡은 Worker).
 * index: (status, priority, created_at) 우선순위 큐 조회용, lock_expires_at, (document_version_id, embedding_model_id),
 * locked_by_worker_id.
 *
 * <p>주의사항: Worker는 이 테이블을 lock(locked_by_worker_id/locked_at/lock_expires_at 설정)한 뒤 PROCESSING으로
 * 전환한다. lock_expires_at이 경과했는데도 완료되지 않으면 다른 Worker가 재처리할 수 있어야 한다.
 * 이는 Worker 장애 복구의 핵심 메커니즘이다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_jobs",
        indexes = {
                @Index(name = "idx_embedding_jobs_status_priority_created_at", columnList = "status, priority, created_at"),
                @Index(name = "idx_embedding_jobs_lock_expires_at", columnList = "lock_expires_at"),
                @Index(name = "idx_embedding_jobs_document_version_id_embedding_model_id", columnList = "document_version_id, embedding_model_id"),
                @Index(name = "idx_embedding_jobs_locked_by_worker_id", columnList = "locked_by_worker_id")
        }
)
public class EmbeddingJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 인덱싱 대상 문서 버전
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_version_id", nullable = false)
    private DocumentVersion documentVersion;

    // 사용할 임베딩 모델
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_model_id", nullable = false)
    private EmbeddingModel embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmbeddingJobStatus status;

    @Column(nullable = false)
    private int priority;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry_count", nullable = false)
    private int maxRetryCount;

    // 이 job을 lock한 Worker, lock되지 않았으면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by_worker_id")
    private WorkerNode lockedByWorker;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    // lock 만료 시각, 경과 시 다른 Worker가 재처리 가능
    @Column(name = "lock_expires_at")
    private LocalDateTime lockExpiresAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder
    public EmbeddingJob(DocumentVersion documentVersion, EmbeddingModel embeddingModel, EmbeddingJobStatus status,
                        int priority, int maxRetryCount) {
        this.documentVersion = documentVersion;
        this.embeddingModel = embeddingModel;
        this.status = status != null ? status : EmbeddingJobStatus.PENDING;
        this.priority = priority;
        this.retryCount = 0;
        this.maxRetryCount = maxRetryCount;
    }

    public void lock(WorkerNode workerNode, LocalDateTime lockedAt, LocalDateTime lockExpiresAt) {
        this.lockedByWorker = workerNode;
        this.lockedAt = lockedAt;
        this.lockExpiresAt = lockExpiresAt;
    }

    public void markProcessing(LocalDateTime startedAt) {
        this.status = EmbeddingJobStatus.PROCESSING;
        this.startedAt = startedAt;
    }

    public void markIndexed(LocalDateTime completedAt) {
        this.status = EmbeddingJobStatus.INDEXED;
        this.completedAt = completedAt;
    }

    public void markFailed(String errorCode, String errorMessage, LocalDateTime failedAt) {
        this.status = EmbeddingJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = failedAt;
    }

    public void increaseRetryCount() {
        this.retryCount++;
    }
}
