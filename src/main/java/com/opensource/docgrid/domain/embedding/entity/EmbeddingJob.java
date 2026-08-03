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
 * index: (status, priority, created_at) 우선순위 큐 조회용, (status, next_retry_at) Retry 실행 가능 시각 조회용,
 * lock_expires_at, (document_version_id, embedding_model_id), locked_by_worker_id.
 *
 * <p>주의사항: Worker는 Claim 시 PROCESSING 상태, 소유 Worker, UUID Claim Token, Lease 시작·만료 시각을
 * 함께 기록한다. DB 행 잠금은 Claim Transaction 동안의 중복 선택을 막고, Lease와 Claim Token은
 * Transaction 종료 후에도 현재 소유권을 식별한다. lock_expires_at이 경과했는데도 완료되지 않으면 후속
 * 복구 작업이 새로운 Token으로 다른 Worker에게 재할당할 수 있어야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "embedding_jobs",
        indexes = {
                @Index(name = "idx_embedding_jobs_status_priority_created_at", columnList = "status, priority, created_at"),
                @Index(name = "idx_embedding_jobs_status_next_retry_at", columnList = "status, next_retry_at"),
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

    // 현재 Claim의 Lease 만료 시각으로, 후속 장애 복구가 소유권 회수 가능 여부를 판단하는 기준이다.
    @Column(name = "lock_expires_at")
    private LocalDateTime lockExpiresAt;

    // 같은 Job이 다시 Claim됐을 때 과거 Worker의 늦은 완료 요청을 구분하는 소유권 증명 값이다.
    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    // PENDING Retry Job이 다시 Claim 가능해지는 시각이며 null이면 즉시 실행할 수 있다.
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

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

    /**
     * PENDING Job을 PROCESSING으로 전환하면서 이번 Claim의 소유권 정보를 원자적으로 반영한다.
     *
     * <p>이 메서드는 DB 행 잠금을 획득한 Transaction 안에서 호출해야 한다. Entity 내부 상태를 한 메서드에서
     * 함께 바꿔 상태만 PROCESSING이고 Lease가 없는 불완전한 변경을 방지한다.
     *
     * @param workerNode Job을 처리할 Worker 실행 인스턴스
     * @param claimToken 이번 Claim을 식별하는 UUID Token
     * @param claimedAt Lease 시작 시각
     * @param lockExpiresAt Lease 만료 시각
     */
    public void claim(WorkerNode workerNode, String claimToken, LocalDateTime claimedAt,
                      LocalDateTime lockExpiresAt) {
        // 1. 이미 처리 중이거나 완료된 Job의 소유권을 덮어쓰지 않도록 상태 전이를 제한한다.
        if (status != EmbeddingJobStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태의 Job만 Claim할 수 있습니다.");
        }

        // 2. 처리 상태와 현재 소유 Worker 및 Token을 함께 설정한다.
        this.status = EmbeddingJobStatus.PROCESSING;
        this.lockedByWorker = workerNode;
        this.claimToken = claimToken;

        // 3. DB 행 잠금 이후에도 소유권 유효 기간을 판단할 수 있도록 Lease 시간을 기록한다.
        this.lockedAt = claimedAt;
        this.lockExpiresAt = lockExpiresAt;
        this.nextRetryAt = null;

        // 4. startedAt은 전체 처리의 최초 시작 시각이므로 향후 재Claim에서도 기존 값을 보존한다.
        if (startedAt == null) {
            this.startedAt = claimedAt;
        }
    }

    /**
     * 현재 처리 중인 Job을 최종 인덱싱 완료 상태로 전환한다.
     *
     * <p>Claim 소유권 정보는 완료 재생과 감사에 사용하므로 완료 후에도 보존한다.
     */
    public void markIndexed(LocalDateTime completedAt) {
        // 완료 Transaction만 PROCESSING Job을 종결할 수 있어야 늦은 요청이 결과를 덮어쓰지 않는다.
        if (status != EmbeddingJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 Job만 INDEXED로 전환할 수 있습니다.");
        }
        this.status = EmbeddingJobStatus.INDEXED;
        this.completedAt = completedAt;
        // Attempt 이력에 실패 원인이 남으므로 현재 Job Snapshot에서는 과거 Retry 오류를 제거한다.
        this.failedAt = null;
        this.nextRetryAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * 현재 Claim을 실패한 Attempt 이력으로 남기고 Job을 지정 시각 이후의 PENDING Queue로 복귀시킨다.
     *
     * <p>현재 소유권을 모두 제거해야 과거 Worker의 Token이 후속 단계 저장 권한으로 재사용되지 않는다.
     */
    public void scheduleRetry(String errorCode, String errorMessage, LocalDateTime nextRetryAt) {
        // 1. 처리 중인 현재 Claim만 Queue로 되돌릴 수 있다.
        if (status != EmbeddingJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 Job만 Retry를 예약할 수 있습니다.");
        }
        // 2. 최대 횟수와 같아진 Job은 별도의 최종 실패 전이로 종결해야 한다.
        if (retryCount >= maxRetryCount) {
            throw new IllegalStateException("Embedding Job Retry 횟수를 모두 소진했습니다.");
        }
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("다음 Retry 시각은 필수입니다.");
        }

        // 3. Queue 상태와 실행 가능 시각 및 최신 오류 Snapshot을 함께 기록한다.
        this.status = EmbeddingJobStatus.PENDING;
        this.retryCount++;
        this.nextRetryAt = nextRetryAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = null;

        // 4. 새 Claim이 새로운 소유권을 발급하도록 과거 Worker, Token과 Lease를 모두 해제한다.
        this.lockedByWorker = null;
        this.claimToken = null;
        this.lockedAt = null;
        this.lockExpiresAt = null;
    }

    public boolean hasRemainingRetries() {
        return retryCount < maxRetryCount;
    }

    /**
     * 현재 PROCESSING Claim의 최초 잠금 시각과 소유권은 유지하고 Lease 만료 시각만 연장한다.
     *
     * <p>Service가 Job 행 잠금, 현재 Worker·Token과 기존 Lease 유효성을 먼저 검증해야 한다. Entity는
     * 갱신이 기존 만료 시각을 줄이거나 이미 끝난 Job에 새 소유권처럼 적용되는 것을 마지막으로 방어한다.
     *
     * @param renewedAt Lease 갱신 기준 시각
     * @param renewedLockExpiresAt 새 Lease 만료 시각
     */
    public void renewLease(
        LocalDateTime renewedAt,
        LocalDateTime renewedLockExpiresAt
    ) {
        // 1. 현재 소유권을 가진 처리 중 Job 이외의 종료·대기 상태는 갱신하지 않는다.
        if (status != EmbeddingJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 Job Lease만 갱신할 수 있습니다.");
        }
        // 2. 현재 Lease가 이미 만료됐거나 소유권 시간이 누락된 모순 상태를 갱신으로 숨기지 않는다.
        if (renewedAt == null
            || lockedAt == null
            || lockExpiresAt == null
            || !lockExpiresAt.isAfter(renewedAt)) {
            throw new IllegalStateException("유효한 현재 Lease만 갱신할 수 있습니다.");
        }
        // 3. 새 만료 시각은 갱신 기준 이후이며 기존 만료 시각을 실제로 연장해야 한다.
        if (renewedLockExpiresAt == null
            || !renewedLockExpiresAt.isAfter(renewedAt)
            || !renewedLockExpiresAt.isAfter(lockExpiresAt)) {
            throw new IllegalArgumentException("새 Lease 만료 시각은 현재 Lease보다 늦어야 합니다.");
        }

        // 4. lockedAt, Worker와 Claim Token은 같은 Claim 세대의 감사·소유권 정보이므로 보존한다.
        this.lockExpiresAt = renewedLockExpiresAt;
    }

    public void markFailed(String errorCode, String errorMessage, LocalDateTime failedAt) {
        // 현재 Claim을 보유한 처리 중 Job만 최종 실패로 종결할 수 있다.
        if (status != EmbeddingJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 Job만 FAILED로 전환할 수 있습니다.");
        }
        this.status = EmbeddingJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = failedAt;
        this.nextRetryAt = null;
    }

}
