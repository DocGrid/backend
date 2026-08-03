package com.opensource.docgrid.domain.embedding.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

/**
 * Embedding Job의 Claim·Retry 예약·인덱싱 완료·실패 상태 전이와 소유권 불변식을 검증하는 Entity 단위 테스트.
 *
 * <p>PENDING Job이 PROCESSING으로 바뀔 때 Worker, Token, Lease, 최초 시작 시각이 함께 기록되는지와
 * 이미 Claim된 Job의 소유권 덮어쓰기가 차단되는지 확인한다.
 */
@DisplayName("EmbeddingJob 테스트")
class EmbeddingJobTest {

    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 7, 22, 15, 0);
    private static final LocalDateTime EXPIRES_AT = CLAIMED_AT.plusMinutes(5);
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";

    @Test
    @DisplayName("PENDING Job을 Claim하면 PROCESSING과 소유권 정보를 함께 기록한다")
    void claim_recordsProcessingOwnership() {
        EmbeddingJob embeddingJob = createPendingJob();
        WorkerNode workerNode = createActiveWorker();

        embeddingJob.claim(workerNode, CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        assertThat(embeddingJob.getLockedByWorker()).isSameAs(workerNode);
        assertThat(embeddingJob.getClaimToken()).isEqualTo(CLAIM_TOKEN);
        assertThat(embeddingJob.getLockedAt()).isEqualTo(CLAIMED_AT);
        assertThat(embeddingJob.getLockExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(embeddingJob.getStartedAt()).isEqualTo(CLAIMED_AT);
    }

    @Test
    @DisplayName("PENDING이 아닌 Job은 다시 Claim할 수 없다")
    void claim_throws_when_jobIsNotPending() {
        EmbeddingJob embeddingJob = createPendingJob();
        WorkerNode workerNode = createActiveWorker();
        embeddingJob.claim(workerNode, CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);

        assertThatThrownBy(() -> embeddingJob.claim(
            workerNode,
            "8d242ac5-0916-4e1c-a781-1f7b932f989b",
            CLAIMED_AT.plusSeconds(1),
            EXPIRES_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("PENDING 상태의 Job만 Claim할 수 있습니다.");
    }

    @Test
    @DisplayName("PROCESSING Job만 INDEXED로 완료할 수 있다")
    void markIndexed_acceptsOnlyProcessingJob() {
        EmbeddingJob processingJob = createPendingJob();
        processingJob.claim(createActiveWorker(), CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);
        LocalDateTime completedAt = CLAIMED_AT.plusSeconds(3);

        processingJob.markIndexed(completedAt);

        assertThat(processingJob.getStatus()).isEqualTo(EmbeddingJobStatus.INDEXED);
        assertThat(processingJob.getCompletedAt()).isEqualTo(completedAt);

        EmbeddingJob pendingJob = createPendingJob();
        assertThatThrownBy(() -> pendingJob.markIndexed(completedAt))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("PROCESSING 상태의 Job만 INDEXED로 전환할 수 있습니다.");
        assertThat(pendingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    @Test
    @DisplayName("PROCESSING Job의 Retry를 예약하면 횟수와 시각을 기록하고 현재 소유권을 해제한다")
    void scheduleRetry_requeuesJobAndReleasesOwnership() {
        EmbeddingJob embeddingJob = createPendingJob();
        embeddingJob.claim(createActiveWorker(), CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);
        LocalDateTime nextRetryAt = CLAIMED_AT.plusSeconds(10);

        embeddingJob.scheduleRetry("STORAGE_UNAVAILABLE", "Storage timeout", nextRetryAt);

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(embeddingJob.getRetryCount()).isEqualTo(1);
        assertThat(embeddingJob.getNextRetryAt()).isEqualTo(nextRetryAt);
        assertThat(embeddingJob.getErrorCode()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(embeddingJob.getErrorMessage()).isEqualTo("Storage timeout");
        assertThat(embeddingJob.getLockedByWorker()).isNull();
        assertThat(embeddingJob.getClaimToken()).isNull();
        assertThat(embeddingJob.getLockedAt()).isNull();
        assertThat(embeddingJob.getLockExpiresAt()).isNull();
        assertThat(embeddingJob.getStartedAt()).isEqualTo(CLAIMED_AT);
    }

    @Test
    @DisplayName("Retry로 복귀한 Job을 다시 Claim하면 예약 시각을 소비하고 최초 시작 시각을 보존한다")
    void claim_clearsRetryScheduleAndPreservesFirstStart() {
        EmbeddingJob embeddingJob = createPendingJob();
        WorkerNode workerNode = createActiveWorker();
        embeddingJob.claim(workerNode, CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);
        embeddingJob.scheduleRetry("STORAGE_UNAVAILABLE", "Storage timeout", CLAIMED_AT.plusSeconds(10));

        LocalDateTime reclaimedAt = CLAIMED_AT.plusSeconds(10);
        embeddingJob.claim(
            workerNode,
            "8d242ac5-0916-4e1c-a781-1f7b932f989b",
            reclaimedAt,
            reclaimedAt.plusMinutes(5)
        );

        assertThat(embeddingJob.getNextRetryAt()).isNull();
        assertThat(embeddingJob.getStartedAt()).isEqualTo(CLAIMED_AT);
        assertThat(embeddingJob.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Retry 횟수를 모두 소진한 Job은 다시 예약할 수 없다")
    void scheduleRetry_throws_when_retriesAreExhausted() {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(1)
            .build();
        WorkerNode workerNode = createActiveWorker();
        embeddingJob.claim(workerNode, CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);
        embeddingJob.scheduleRetry("WORKER_INTERNAL_ERROR", "temporary", CLAIMED_AT.plusSeconds(10));
        LocalDateTime reclaimedAt = CLAIMED_AT.plusSeconds(10);
        embeddingJob.claim(
            workerNode,
            "8d242ac5-0916-4e1c-a781-1f7b932f989b",
            reclaimedAt,
            reclaimedAt.plusMinutes(5)
        );

        assertThat(embeddingJob.hasRemainingRetries()).isFalse();
        assertThatThrownBy(() -> embeddingJob.scheduleRetry(
            "WORKER_INTERNAL_ERROR",
            "temporary",
            reclaimedAt.plusSeconds(20)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("Embedding Job Retry 횟수를 모두 소진했습니다.");
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("PROCESSING이 아닌 Job은 Retry를 예약할 수 없다")
    void scheduleRetry_throws_when_jobIsNotProcessing() {
        EmbeddingJob pendingJob = createPendingJob();

        assertThatThrownBy(() -> pendingJob.scheduleRetry(
            "STORAGE_UNAVAILABLE",
            "Storage timeout",
            CLAIMED_AT.plusSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("PROCESSING 상태의 Job만 Retry를 예약할 수 있습니다.");
        assertThat(pendingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    @Test
    @DisplayName("다음 Retry 시각이 없으면 예약할 수 없다")
    void scheduleRetry_throws_when_nextRetryAtIsNull() {
        EmbeddingJob embeddingJob = createPendingJob();
        embeddingJob.claim(createActiveWorker(), CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);

        assertThatThrownBy(() -> embeddingJob.scheduleRetry(
            "STORAGE_UNAVAILABLE",
            "Storage timeout",
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("다음 Retry 시각은 필수입니다.");
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        assertThat(embeddingJob.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("PROCESSING Job만 최종 FAILED로 종료하고 예약 시각을 제거할 수 있다")
    void markFailed_acceptsOnlyProcessingJob() {
        EmbeddingJob processingJob = createPendingJob();
        processingJob.claim(createActiveWorker(), CLAIM_TOKEN, CLAIMED_AT, EXPIRES_AT);
        LocalDateTime failedAt = CLAIMED_AT.plusSeconds(3);

        processingJob.markFailed("DOCUMENT_CONTENT_INVALID", "Unsupported content", failedAt);

        assertThat(processingJob.getStatus()).isEqualTo(EmbeddingJobStatus.FAILED);
        assertThat(processingJob.getErrorCode()).isEqualTo("DOCUMENT_CONTENT_INVALID");
        assertThat(processingJob.getErrorMessage()).isEqualTo("Unsupported content");
        assertThat(processingJob.getFailedAt()).isEqualTo(failedAt);
        assertThat(processingJob.getNextRetryAt()).isNull();

        EmbeddingJob pendingJob = createPendingJob();
        assertThatThrownBy(() -> pendingJob.markFailed(
            "DOCUMENT_CONTENT_INVALID",
            "Unsupported content",
            failedAt
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("PROCESSING 상태의 Job만 FAILED로 전환할 수 있습니다.");
        assertThat(pendingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    private EmbeddingJob createPendingJob() {
        return EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
    }

    private WorkerNode createActiveWorker() {
        return WorkerNode.builder()
            .workerName("indexing-worker")
            .instanceId("worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(CLAIMED_AT)
            .startedAt(CLAIMED_AT.minusMinutes(1))
            .build();
    }
}
