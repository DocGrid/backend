package com.opensource.docgrid.domain.worker.service;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Attempt 시작 이후 Worker Pipeline 오류를 기존 인덱싱 실패 전이 Service에 안전하게 보고한다.
 *
 * <p>분류된 고정 메시지만 저장하고 소유권 상실 오류는 보고하지 않는다. 보고 자체의 실패도 비동기 Job
 * Thread 밖으로 전파하지 않아 남은 상태는 Lease 만료 복구가 처리할 수 있게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerIndexingFailureReporter {

    private final WorkerIndexingFailureClassifier failureClassifier;
    private final DocumentIndexingFailureService failureService;

    /**
     * 현재 Attempt 오류를 분류해 보고하고, 과거 소유권이거나 보고가 실패하면 안전한 진단만 남긴다.
     */
    public void report(
        ClaimedEmbeddingJobResponse claimedJob,
        Long attemptId,
        RuntimeException exception
    ) {
        // 1. 원본 예외를 저장 가능한 실패 유형·안전한 메시지와 소유권 상실 여부로 정규화한다.
        WorkerIndexingFailure failure = failureClassifier.classify(exception);

        // 2. 소유권 상실은 새 소유자의 상태 전이를 침범하지 않도록 실패 API에 보고하지 않는다.
        if (!failure.reportable()) {
            log.warn(
                "소유권을 잃은 Worker 인덱싱 실행을 중단합니다. workerId={}, jobId={}, attemptId={}, errorCode={}",
                claimedJob.workerId(),
                claimedJob.jobId(),
                attemptId,
                failure.diagnosticCode()
            );
            return;
        }

        // 3. 현재 소유권에서 발생한 오류만 공통 실패 전이 Service에 전달한다.
        try {
            failureService.fail(
                claimedJob.jobId(),
                attemptId,
                new FailDocumentIndexingRequest(
                    claimedJob.workerId(),
                    claimedJob.claimToken(),
                    failure.failureType(),
                    failure.safeMessage()
                ),
                failure.minimumRetryDelay()
            );
            log.info(
                "Worker 인덱싱 실패를 기록했습니다. workerId={}, jobId={}, attemptId={}, failureType={}, errorCode={}",
                claimedJob.workerId(),
                claimedJob.jobId(),
                attemptId,
                failure.failureType(),
                failure.diagnosticCode()
            );
        } catch (RuntimeException reportException) {
            // 4. 보고 실패를 실행 Thread 밖으로 다시 던지지 않아 Lease 복구라는 최종 안전망을 유지한다.
            log.error(
                "Worker 인덱싱 실패를 기록하지 못했습니다. workerId={}, jobId={}, attemptId={}, "
                    + "originalErrorCode={}, reportErrorCode={}",
                claimedJob.workerId(),
                claimedJob.jobId(),
                attemptId,
                failure.diagnosticCode(),
                diagnosticCode(reportException)
            );
        }
    }

    /**
     * 로그에 원본 메시지나 Provider 응답을 노출하지 않고 안정적인 오류 식별자만 추출한다.
     */
    private String diagnosticCode(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
