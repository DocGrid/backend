package com.opensource.docgrid.domain.worker.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
import com.opensource.docgrid.domain.worker.execution.WorkerExecutionSlotPool.WorkerExecutionSlot;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Claim된 Embedding Job을 기존 인덱싱 Service들로 끝까지 실행하는 Worker 오케스트레이터다.
 *
 * <p>자체 Transaction을 만들지 않아 파일 저장소와 Embedding Provider 호출 중 DB 잠금을 유지하지 않는다.
 * 문서 버전 상태 Snapshot은 시작 단계만 결정하고 각 Service가 현재 Claim과 상태를 다시 검증한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerIndexingPipeline {

    private final EmbeddingJobAttemptService embeddingJobAttemptService;
    private final DocumentIndexingStageQueryService stageQueryService;
    private final DocumentParsingService documentParsingService;
    private final DocumentEmbeddingService documentEmbeddingService;
    private final DocumentIndexingCompletionService completionService;
    private final WorkerIndexingFailureReporter failureReporter;
    private final WorkerLeaseRenewalManager leaseRenewalManager;

    /**
     * 현재 Claim의 Attempt를 시작하고 문서 상태에 맞는 단계부터 완료까지 실행한다.
     *
     * <p>호출자가 넘긴 실행 Slot의 소유권을 인수하며 성공과 예외 경로 모두에서 정확히 한 번 반환한다.
     */
    public void execute(
        ClaimedEmbeddingJobResponse claimedJob,
        WorkerExecutionSlot executionSlot
    ) {
        try (executionSlot) {
            validateClaim(claimedJob);

            // 1. 실제 실행 Context를 먼저 기록해 이후 단계와 실패 보고가 같은 Attempt를 식별하게 한다.
            StartedEmbeddingJobAttemptResponse attempt = embeddingJobAttemptService.start(
                claimedJob.jobId(),
                new StartEmbeddingJobAttemptRequest(claimedJob.workerId(), claimedJob.claimToken())
            ).response();

            // 2. 실제 Attempt 수명에 맞춰 Lease 갱신을 시작하고 모든 종료 경로에서 예약을 해제한다.
            try (WorkerLeaseRenewalHandle leaseHandle = leaseRenewalManager.start(claimedJob)) {
                leaseHandle.ensureOwned();

                // 3. 경로 선택용 상태 Snapshot을 조회하고 이미 완료한 단계는 다시 외부 호출하지 않는다.
                DocumentVersionStatus initialStatus = stageQueryService.getStatus(
                    claimedJob.documentVersionId()
                );
                log.info(
                    "Worker 인덱싱 실행을 시작합니다. workerId={}, jobId={}, attemptId={}, initialStatus={}",
                    claimedJob.workerId(),
                    claimedJob.jobId(),
                    attempt.attemptId(),
                    initialStatus
                );
                executeFromCurrentStage(claimedJob, attempt.attemptId(), initialStatus, leaseHandle);

                // 4. 전체 Embedding Set과 현재 실행 소유권을 최종 검증해 검색 가능한 Version으로 확정한다.
                leaseHandle.ensureOwned();
                completionService.complete(
                    claimedJob.jobId(),
                    attempt.attemptId(),
                    new CompleteDocumentIndexingRequest(claimedJob.workerId(), claimedJob.claimToken())
                );
                log.info(
                    "Worker 인덱싱 실행을 완료했습니다. workerId={}, jobId={}, attemptId={}",
                    claimedJob.workerId(),
                    claimedJob.jobId(),
                    attempt.attemptId()
                );
            } catch (RuntimeException exception) {
                // 5. 실제 Attempt가 시작된 뒤의 오류만 제한된 실패 계약으로 기록한다.
                failureReporter.report(claimedJob, attempt.attemptId(), exception);
            }
        }
    }

    private void validateClaim(ClaimedEmbeddingJobResponse claimedJob) {
        if (claimedJob == null
            || claimedJob.status() != EmbeddingJobStatus.PROCESSING
            || claimedJob.jobId() == null
            || claimedJob.workerId() == null
            || claimedJob.documentVersionId() == null
            || claimedJob.claimToken() == null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        }
    }

    private void executeFromCurrentStage(
        ClaimedEmbeddingJobResponse claimedJob,
        Long attemptId,
        DocumentVersionStatus initialStatus,
        WorkerLeaseRenewalHandle leaseHandle
    ) {
        // 1. 새 Version과 중단된 Parsing은 기존 Chunk Service의 멱등·재개 계약으로 CHUNKED까지 진행한다.
        if (initialStatus == DocumentVersionStatus.UPLOADED
            || initialStatus == DocumentVersionStatus.PARSING) {
            documentParsingService.createChunks(
                claimedJob.jobId(),
                attemptId,
                new CreateDocumentChunksRequest(claimedJob.workerId(), claimedJob.claimToken())
            );
            leaseHandle.ensureOwned();
        } else if (initialStatus != DocumentVersionStatus.CHUNKED
            && initialStatus != DocumentVersionStatus.EMBEDDING) {
            throw new DocGridException(ErrorCode.INDEXING_STATUS_INCONSISTENT);
        }

        // 2. CHUNKED 또는 중단된 EMBEDDING 상태를 기존 Service의 생성·재생 계약으로 완료한다.
        leaseHandle.ensureOwned();
        documentEmbeddingService.createEmbeddings(
            claimedJob.jobId(),
            attemptId,
            new CreateDocumentEmbeddingsRequest(claimedJob.workerId(), claimedJob.claimToken())
        );
        leaseHandle.ensureOwned();
    }
}
