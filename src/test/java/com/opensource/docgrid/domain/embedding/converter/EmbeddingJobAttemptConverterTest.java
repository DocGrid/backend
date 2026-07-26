package com.opensource.docgrid.domain.embedding.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;

/**
 * Embedding Job Attempt Entity가 Token 비노출 시작 응답으로 정확히 변환되는지 검증하는 단위 테스트.
 *
 * <p>연관 Entity 대신 Job·Worker 식별자를 사용하고 외부 계약에 필요한 시작 정보만 반환하는지 확인한다.
 */
@DisplayName("EmbeddingJobAttemptConverter 테스트")
class EmbeddingJobAttemptConverterTest {

    private static final Long ATTEMPT_ID = 100L;
    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 26, 21, 40);

    private final EmbeddingJobAttemptConverter converter = new EmbeddingJobAttemptConverter();

    @Test
    @DisplayName("Attempt 시작 식별자와 상태를 응답으로 변환한다")
    void toStartedResponse_convertsAttemptWithoutClaimToken() {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        WorkerNode workerNode = WorkerNode.builder()
            .workerName("attempt-worker")
            .instanceId("attempt-worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(STARTED_AT)
            .startedAt(STARTED_AT.minusMinutes(1))
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);

        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(workerNode)
            .attemptNo(2)
            .claimToken("34c19d16-6ae1-4f6a-a35d-0123456789ab")
            .startedAt(STARTED_AT)
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);

        StartedEmbeddingJobAttemptResponse response = converter.toStartedResponse(attempt);

        assertThat(response.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(response.jobId()).isEqualTo(JOB_ID);
        assertThat(response.attemptNo()).isEqualTo(2);
        assertThat(response.workerId()).isEqualTo(WORKER_ID);
        assertThat(response.status()).isEqualTo(AttemptStatus.STARTED);
        assertThat(response.startedAt()).isEqualTo(STARTED_AT);
        assertThat(StartedEmbeddingJobAttemptResponse.class.getRecordComponents())
            .extracting(component -> component.getName())
            .doesNotContain("claimToken", "errorCode", "errorMessage");
    }
}
