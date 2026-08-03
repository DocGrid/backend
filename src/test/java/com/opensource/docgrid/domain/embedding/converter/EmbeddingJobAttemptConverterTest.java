package com.opensource.docgrid.domain.embedding.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Embedding Job Attempt Entity가 Token과 오류 메시지를 노출하지 않는 시작·실패 응답으로 변환되는지 검증한다.
 *
 * <p>연관 Entity 대신 Job·Worker 식별자를 사용하고 외부 계약에 필요한 정보만 반환하며, 알 수 없는 실패
 * 코드는 데이터 불일치로 거부하는지 확인한다.
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

    @Test
    @DisplayName("종료된 Attempt의 불변 실패 정보만 실패 응답으로 변환한다")
    void toFailureResponse_convertsPersistedFailure() {
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

        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(workerNode)
            .attemptNo(2)
            .claimToken("34c19d16-6ae1-4f6a-a35d-0123456789ab")
            .startedAt(STARTED_AT)
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
        LocalDateTime failedAt = STARTED_AT.plusSeconds(4);
        attempt.markFailed(
            failedAt,
            4_000L,
            IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE.name(),
            "Embedding provider request timed out"
        );

        DocumentIndexingFailureResponse response = converter.toFailureResponse(attempt);

        assertThat(response.jobId()).isEqualTo(JOB_ID);
        assertThat(response.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(response.attemptNo()).isEqualTo(2);
        assertThat(response.attemptStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(response.failureType()).isEqualTo(IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE);
        assertThat(response.failedAt()).isEqualTo(failedAt);
        assertThat(response.durationMs()).isEqualTo(4_000L);
        assertThat(DocumentIndexingFailureResponse.class.getRecordComponents())
            .extracting(component -> component.getName())
            .doesNotContain("claimToken", "errorCode", "errorMessage", "jobStatus", "nextRetryAt");
    }

    @Test
    @DisplayName("알 수 없는 저장 실패 코드는 데이터 불일치로 거부한다")
    void toFailureResponse_throws_when_failureCodeIsUnknown() {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .attemptNo(2)
            .startedAt(STARTED_AT)
            .endedAt(STARTED_AT.plusSeconds(4))
            .durationMs(4_000L)
            .status(AttemptStatus.FAILED)
            .errorCode("LEGACY_UNKNOWN_FAILURE")
            .errorMessage("legacy failure")
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);

        assertThatThrownBy(() -> converter.toFailureResponse(attempt))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
    }
}
