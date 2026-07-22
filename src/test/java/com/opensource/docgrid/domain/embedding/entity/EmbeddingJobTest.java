package com.opensource.docgrid.domain.embedding.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

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
