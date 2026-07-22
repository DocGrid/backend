package com.opensource.docgrid.domain.embedding.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;

@DisplayName("EmbeddingJobConverter 테스트")
class EmbeddingJobConverterTest {

    private static final Long JOB_ID = 10L;
    private static final Long VERSION_ID = 5L;
    private static final Long MODEL_ID = 2L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 7, 22, 15, 0);

    private final EmbeddingJobConverter embeddingJobConverter = new EmbeddingJobConverter();

    @Test
    @DisplayName("Claim된 Job의 소유권과 대상 ID를 응답으로 변환한다")
    void toClaimedResponse_convertsClaimedJob() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(CLAIMED_AT);
        DocumentVersion documentVersion = DocumentVersion.builder()
            .versionNo(1)
            .status(DocumentVersionStatus.UPLOADED)
            .build();
        EmbeddingModel embeddingModel = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);
        ReflectionTestUtils.setField(embeddingModel, "id", MODEL_ID);

        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .embeddingModel(embeddingModel)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        embeddingJob.claim(workerNode, CLAIM_TOKEN, CLAIMED_AT, CLAIMED_AT.plusMinutes(5));

        ClaimedEmbeddingJobResponse response = embeddingJobConverter.toClaimedResponse(embeddingJob);

        assertThat(response.jobId()).isEqualTo(JOB_ID);
        assertThat(response.status()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        assertThat(response.workerId()).isEqualTo(WorkerNodeFixture.WORKER_ID);
        assertThat(response.documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(response.embeddingModelId()).isEqualTo(MODEL_ID);
        assertThat(response.claimToken()).isEqualTo(CLAIM_TOKEN);
        assertThat(response.lockedAt()).isEqualTo(CLAIMED_AT);
        assertThat(response.lockExpiresAt()).isEqualTo(CLAIMED_AT.plusMinutes(5));
    }
}
