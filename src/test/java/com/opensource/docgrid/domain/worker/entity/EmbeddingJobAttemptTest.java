package com.opensource.docgrid.domain.worker.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

/**
 * Embedding Job Attempt의 시작 상태 생성 계약과 성공·실패 상태 전이 Guard를 검증하는 Entity 단위 테스트.
 *
 * <p>신규 시작 경로가 Job, Worker, Claim Token, 번호와 시각을 함께 보존하며 종료 정보는 시작 시점에
 * 비어 있는지 확인한다.
 */
@DisplayName("EmbeddingJobAttempt 테스트")
class EmbeddingJobAttemptTest {

    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 26, 21, 40);

    @Test
    @DisplayName("상태를 생략한 신규 Attempt는 STARTED와 Claim 실행 정보를 함께 기록한다")
    void create_recordsStartedClaimExecution() {
        EmbeddingJob embeddingJob = createJob();
        WorkerNode workerNode = createWorker();

        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(workerNode)
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .startedAt(STARTED_AT)
            .build();

        assertThat(attempt.getEmbeddingJob()).isSameAs(embeddingJob);
        assertThat(attempt.getWorkerNode()).isSameAs(workerNode);
        assertThat(attempt.getAttemptNo()).isEqualTo(1);
        assertThat(attempt.getClaimToken()).isEqualTo(CLAIM_TOKEN);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.STARTED);
        assertThat(attempt.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(attempt.getEndedAt()).isNull();
        assertThat(attempt.getDurationMs()).isNull();
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("시작된 Attempt를 성공 상태로 종료한다")
    void markSuccess_recordsCompletion() {
        EmbeddingJobAttempt attempt = createStartedAttempt();
        LocalDateTime endedAt = STARTED_AT.plusSeconds(3);

        attempt.markSuccess(endedAt, 3_000L);

        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempt.getEndedAt()).isEqualTo(endedAt);
        assertThat(attempt.getDurationMs()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("종결된 Attempt는 다시 성공 처리할 수 없다")
    void markSuccess_rejectsCompletedAttempt() {
        EmbeddingJobAttempt attempt = createStartedAttempt();
        LocalDateTime firstEndedAt = STARTED_AT.plusSeconds(3);
        attempt.markSuccess(firstEndedAt, 3_000L);

        assertThatThrownBy(() -> attempt.markSuccess(STARTED_AT.plusSeconds(5), 5_000L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("STARTED 상태의 Attempt만 SUCCESS로 전환할 수 있습니다.");
        assertThat(attempt.getEndedAt()).isEqualTo(firstEndedAt);
        assertThat(attempt.getDurationMs()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("시작된 Attempt를 실패 상태와 오류 정보로 종료한다")
    void markFailed_recordsFailure() {
        EmbeddingJobAttempt attempt = createStartedAttempt();
        LocalDateTime endedAt = STARTED_AT.plusSeconds(2);

        attempt.markFailed(endedAt, 2_000L, "PARSING_FAILED", "문서 파싱 실패");

        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getEndedAt()).isEqualTo(endedAt);
        assertThat(attempt.getDurationMs()).isEqualTo(2_000L);
        assertThat(attempt.getErrorCode()).isEqualTo("PARSING_FAILED");
        assertThat(attempt.getErrorMessage()).isEqualTo("문서 파싱 실패");
    }

    private EmbeddingJobAttempt createStartedAttempt() {
        return EmbeddingJobAttempt.builder()
            .embeddingJob(createJob())
            .workerNode(createWorker())
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .startedAt(STARTED_AT)
            .build();
    }

    private EmbeddingJob createJob() {
        return EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
    }

    private WorkerNode createWorker() {
        return WorkerNode.builder()
            .workerName("attempt-worker")
            .instanceId("attempt-worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(STARTED_AT)
            .startedAt(STARTED_AT.minusMinutes(1))
            .build();
    }
}
