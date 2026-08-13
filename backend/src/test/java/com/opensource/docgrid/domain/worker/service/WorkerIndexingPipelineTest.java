package com.opensource.docgrid.domain.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.document.service.query.DocumentIndexingStageQueryService;
import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentEmbeddingsRequest;
import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool;
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Worker Pipeline의 상태별 단계 선택, Service 순서와 실패·자원 정리 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerIndexingPipeline 테스트")
class WorkerIndexingPipelineTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long WORKER_ID = 1L;
    private static final Long VERSION_ID = 5L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";

    @Mock private EmbeddingJobAttemptService attemptService;
    @Mock private DocumentIndexingStageQueryService stageQueryService;
    @Mock private DocumentParsingService parsingService;
    @Mock private DocumentEmbeddingService embeddingService;
    @Mock private DocumentIndexingCompletionService completionService;
    @Mock private WorkerIndexingFailureReporter failureReporter;
    @Mock private WorkerLeaseRenewalManager leaseRenewalManager;

    private WorkerIndexingPipeline pipeline;
    private ClaimedEmbeddingJobResponse claimedJob;

    @BeforeEach
    void setUp() {
        pipeline = new WorkerIndexingPipeline(
            attemptService,
            stageQueryService,
            parsingService,
            embeddingService,
            completionService,
            failureReporter,
            leaseRenewalManager
        );
        claimedJob = claimedJob();
    }

    @Test
    @DisplayName("UPLOADED 상태는 Attempt, Chunk, Embedding, 완료 순서로 실행한다")
    void execute_runsAllStages_fromUploaded() {
        given(attemptService.start(any(), any(StartEmbeddingJobAttemptRequest.class)))
            .willReturn(startResult());
        WorkerLeaseRenewalHandle leaseHandle = leaseHandle();
        given(leaseRenewalManager.start(claimedJob)).willReturn(leaseHandle);
        given(stageQueryService.getStatus(VERSION_ID)).willReturn(DocumentVersionStatus.UPLOADED);
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(1);

        pipeline.execute(claimedJob, slotPool.tryAcquire().orElseThrow());

        InOrder order = inOrder(
            attemptService,
            stageQueryService,
            parsingService,
            embeddingService,
            completionService
        );
        order.verify(attemptService).start(any(), any(StartEmbeddingJobAttemptRequest.class));
        order.verify(stageQueryService).getStatus(VERSION_ID);
        order.verify(parsingService).createChunks(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.eq(ATTEMPT_ID),
            any(CreateDocumentChunksRequest.class)
        );
        order.verify(embeddingService).createEmbeddings(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.eq(ATTEMPT_ID),
            any(CreateDocumentEmbeddingsRequest.class)
        );
        order.verify(completionService).complete(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.eq(ATTEMPT_ID),
            any(CompleteDocumentIndexingRequest.class)
        );
        assertThat(slotPool.getAvailableSlots()).isOne();
        assertThat(leaseHandle.isClosed()).isTrue();
        then(failureReporter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("CHUNKED 상태는 Chunk를 건너뛰고 Embedding부터 실행한다")
    void execute_skipsChunks_fromChunked() {
        given(attemptService.start(any(), any(StartEmbeddingJobAttemptRequest.class)))
            .willReturn(startResult());
        given(leaseRenewalManager.start(claimedJob)).willReturn(leaseHandle());
        given(stageQueryService.getStatus(VERSION_ID)).willReturn(DocumentVersionStatus.CHUNKED);

        pipeline.execute(claimedJob, slot());

        then(parsingService).shouldHaveNoInteractions();
        then(embeddingService).should().createEmbeddings(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.eq(ATTEMPT_ID),
            any(CreateDocumentEmbeddingsRequest.class)
        );
        then(completionService).should().complete(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.eq(ATTEMPT_ID),
            any(CompleteDocumentIndexingRequest.class)
        );
    }

    @Test
    @DisplayName("Attempt 시작 후 단계 오류는 실패 Reporter에 전달하고 Slot과 Lease를 닫는다")
    void execute_reportsFailure_afterAttemptStart() {
        given(attemptService.start(any(), any(StartEmbeddingJobAttemptRequest.class)))
            .willReturn(startResult());
        WorkerLeaseRenewalHandle leaseHandle = leaseHandle();
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(1);
        given(leaseRenewalManager.start(claimedJob)).willReturn(leaseHandle);
        DocGridException failure = new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        given(stageQueryService.getStatus(VERSION_ID)).willThrow(failure);

        pipeline.execute(claimedJob, slotPool.tryAcquire().orElseThrow());

        then(failureReporter).should().report(claimedJob, ATTEMPT_ID, failure);
        then(embeddingService).shouldHaveNoInteractions();
        then(completionService).shouldHaveNoInteractions();
        assertThat(leaseHandle.isClosed()).isTrue();
        assertThat(slotPool.getAvailableSlots()).isOne();
    }

    @Test
    @DisplayName("Attempt 시작 전 오류는 실패를 합성하지 않고 Slot만 반환한다")
    void execute_propagatesFailure_beforeAttemptStart() {
        WorkerExecutionSlotPool slotPool = new WorkerExecutionSlotPool(1);
        given(attemptService.start(any(), any(StartEmbeddingJobAttemptRequest.class)))
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED));

        assertThatThrownBy(() -> pipeline.execute(
            claimedJob,
            slotPool.tryAcquire().orElseThrow()
        )).isInstanceOf(DocGridException.class);

        then(leaseRenewalManager).should(never()).start(any());
        then(failureReporter).shouldHaveNoInteractions();
        assertThat(slotPool.getAvailableSlots()).isOne();
    }

    private WorkerExecutionSlot slot() {
        return new WorkerExecutionSlotPool(1).tryAcquire().orElseThrow();
    }

    private WorkerLeaseRenewalHandle leaseHandle() {
        return new WorkerLeaseRenewalHandle(JOB_ID, WORKER_ID, CLAIM_TOKEN, ignored -> {
        });
    }

    private StartResult startResult() {
        return new StartResult(
            new StartedEmbeddingJobAttemptResponse(
                ATTEMPT_ID,
                JOB_ID,
                1,
                WORKER_ID,
                AttemptStatus.STARTED,
                LocalDateTime.of(2026, 8, 3, 18, 0)
            ),
            true
        );
    }

    private ClaimedEmbeddingJobResponse claimedJob() {
        return new ClaimedEmbeddingJobResponse(
            JOB_ID,
            EmbeddingJobStatus.PROCESSING,
            WORKER_ID,
            VERSION_ID,
            7L,
            CLAIM_TOKEN,
            LocalDateTime.of(2026, 8, 3, 18, 0),
            LocalDateTime.of(2026, 8, 3, 18, 5)
        );
    }
}
