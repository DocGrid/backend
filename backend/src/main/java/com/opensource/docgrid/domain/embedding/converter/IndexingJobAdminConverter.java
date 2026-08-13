package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;

/**
 * 인덱싱 Job 관리자 조회 Entity를 민감 정보가 제거된 공개 Response DTO로 변환한다.
 *
 * <p>Claim Token, 내부 Error Message와 Event Metadata는 이 경계에서 읽지 않아 Controller 응답으로
 * 전달될 수 없게 한다.
 */
@Component
public class IndexingJobAdminConverter {

    public AdminIndexingJobResponse toJobResponse(EmbeddingJob job) {
        DocumentVersion version = job.getDocumentVersion();
        EmbeddingModel model = job.getEmbeddingModel();
        WorkerNode worker = job.getLockedByWorker();

        return new AdminIndexingJobResponse(
            job.getId(),
            job.getStatus(),
            job.getPriority(),
            job.getRetryCount(),
            job.getMaxRetryCount(),
            job.getNextRetryAt(),
            version.getDocument().getId(),
            version.getDocument().getTitle(),
            version.getId(),
            version.getVersionNo(),
            version.getStatus(),
            model.getId(),
            model.getModelName(),
            model.getModelVersion(),
            worker == null ? null : worker.getId(),
            worker == null ? null : worker.getWorkerName(),
            job.getErrorCode(),
            job.getLockedAt(),
            job.getLockExpiresAt(),
            job.getCreatedAt(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getFailedAt()
        );
    }

    public AdminIndexingJobAttemptResponse toAttemptResponse(EmbeddingJobAttempt attempt) {
        WorkerNode worker = attempt.getWorkerNode();
        return new AdminIndexingJobAttemptResponse(
            attempt.getId(),
            attempt.getAttemptNo(),
            attempt.getStatus(),
            worker == null ? null : worker.getId(),
            worker == null ? null : worker.getWorkerName(),
            attempt.getStartedAt(),
            attempt.getEndedAt(),
            attempt.getDurationMs(),
            attempt.getErrorCode()
        );
    }

    public AdminIndexingEventResponse toEventResponse(IndexingEvent event) {
        return new AdminIndexingEventResponse(
            event.getId(),
            event.getEventType(),
            event.getFromStatus(),
            event.getToStatus(),
            event.getMessage(),
            event.getOccurredAt()
        );
    }
}
