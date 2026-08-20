package com.opensource.docgrid.domain.embedding.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;

@ExtendWith(MockitoExtension.class)
@DisplayName("IndexingJobAdminConverter 테스트")
class IndexingJobAdminConverterTest {

    @Mock private EmbeddingJob job;
    @Mock private DocumentVersion version;
    @Mock private Document document;
    @Mock private EmbeddingModel model;
    @Mock private WorkerNode worker;
    @Mock private EmbeddingJobAttempt attempt;
    @Mock private IndexingEvent event;

    @InjectMocks private IndexingJobAdminConverter converter;

    @Test
    @DisplayName("Job과 연관 Snapshot을 민감 정보 없는 관리자 응답으로 변환한다")
    void toJobResponse_convertsSafeSnapshot() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 8, 10, 0);
        given(job.getId()).willReturn(10L);
        given(job.getStatus()).willReturn(EmbeddingJobStatus.PROCESSING);
        given(job.getDocumentVersion()).willReturn(version);
        given(job.getEmbeddingModel()).willReturn(model);
        given(job.getLockedByWorker()).willReturn(worker);
        given(job.getCreatedAt()).willReturn(createdAt);
        given(version.getDocument()).willReturn(document);
        given(version.getId()).willReturn(5L);
        given(version.getVersionNo()).willReturn(2);
        given(version.getStatus()).willReturn(DocumentVersionStatus.EMBEDDING);
        given(document.getId()).willReturn(3L);
        given(document.getTitle()).willReturn("운영 가이드");
        given(model.getId()).willReturn(1L);
        given(model.getModelName()).willReturn("BAAI/bge-m3");
        given(model.getModelVersion()).willReturn("1");
        given(worker.getId()).willReturn(7L);
        given(worker.getWorkerName()).willReturn("indexing-worker");

        AdminIndexingJobResponse response = converter.toJobResponse(
            job,
            EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED
        );

        assertThat(response.jobId()).isEqualTo(10L);
        assertThat(response.documentId()).isEqualTo(3L);
        assertThat(response.documentVersionStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        assertThat(response.embeddingModelName()).isEqualTo("BAAI/bge-m3");
        assertThat(response.workerId()).isEqualTo(7L);
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("종료 Job의 현재 Worker가 없으면 Worker 필드를 null로 변환한다")
    void toJobResponse_keepsWorkerFieldsNull_whenOwnershipIsReleased() {
        given(job.getDocumentVersion()).willReturn(version);
        given(job.getEmbeddingModel()).willReturn(model);
        given(job.getLockedByWorker()).willReturn(null);
        given(version.getDocument()).willReturn(document);

        AdminIndexingJobResponse response = converter.toJobResponse(
            job,
            EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED
        );

        assertThat(response.workerId()).isNull();
        assertThat(response.workerName()).isNull();
    }

    @Test
    @DisplayName("Attempt와 Event를 공개 가능한 이력으로 변환한다")
    void toHistoryResponses_convertsSafeFields() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 8, 10, 5);
        given(attempt.getId()).willReturn(21L);
        given(attempt.getAttemptNo()).willReturn(2);
        given(attempt.getStatus()).willReturn(AttemptStatus.FAILED);
        given(attempt.getWorkerNode()).willReturn(worker);
        given(worker.getId()).willReturn(7L);
        given(worker.getWorkerName()).willReturn("indexing-worker");
        given(event.getId()).willReturn(31L);
        given(event.getEventType()).willReturn(IndexingEventType.RETRY);
        given(event.getMessage()).willReturn("인덱싱 Job 자동 재시도를 예약했습니다.");
        given(event.getOccurredAt()).willReturn(occurredAt);

        AdminIndexingJobAttemptResponse attemptResponse = converter.toAttemptResponse(attempt);
        AdminIndexingEventResponse eventResponse = converter.toEventResponse(event);

        assertThat(attemptResponse.attemptId()).isEqualTo(21L);
        assertThat(attemptResponse.workerId()).isEqualTo(7L);
        assertThat(eventResponse.eventId()).isEqualTo(31L);
        assertThat(eventResponse.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("관리자 응답 계약에는 소유권과 내부 진단 필드가 존재하지 않는다")
    void responseContracts_doNotDeclareSensitiveFields() {
        assertThat(componentNames(AdminIndexingJobResponse.class))
            .doesNotContain("claimToken", "errorMessage");
        assertThat(componentNames(AdminIndexingJobAttemptResponse.class))
            .doesNotContain("claimToken", "errorMessage");
        assertThat(componentNames(AdminIndexingEventResponse.class))
            .doesNotContain("metadataJson");
    }

    private static String[] componentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
            .map(component -> component.getName())
            .toArray(String[]::new);
    }
}
