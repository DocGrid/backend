package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

/**
 * 조회 화면과 Command가 공유하는 수동 재처리 가능 여부 판정 순서를 검증한다.
 */
@DisplayName("EmbeddingJobManualRetryPolicy 테스트")
class EmbeddingJobManualRetryPolicyTest {

    private static final Long DOCUMENT_ID = 3L;
    private static final Long VERSION_ID = 5L;

    private final EmbeddingJobManualRetryPolicy policy = new EmbeddingJobManualRetryPolicy();

    @Test
    @DisplayName("최신 FAILED Version이고 활성 Job이 없으면 재처리할 수 있다")
    void evaluate_returnsEligible_whenTargetIsLatestAndIdle() {
        RetryTarget target = retryTarget();

        assertThat(policy.evaluate(
            target.job(),
            target.version(),
            target.document(),
            VERSION_ID,
            false
        )).isEqualTo(EmbeddingJobManualRetryEligibility.ELIGIBLE);
    }

    @Test
    @DisplayName("더 최신 Version이 있으면 과거 실패 Job으로 분류한다")
    void evaluate_returnsSupersededVersion_whenNewerVersionExists() {
        RetryTarget target = retryTarget();

        assertThat(policy.evaluate(
            target.job(),
            target.version(),
            target.document(),
            VERSION_ID + 1,
            false
        )).isEqualTo(EmbeddingJobManualRetryEligibility.SUPERSEDED_VERSION);
    }

    @Test
    @DisplayName("같은 Version에 활성 Job이 있으면 중복 재처리를 차단한다")
    void evaluate_returnsLiveJobExists_whenVersionIsAlreadyQueued() {
        RetryTarget target = retryTarget();

        assertThat(policy.evaluate(
            target.job(),
            target.version(),
            target.document(),
            VERSION_ID,
            true
        )).isEqualTo(EmbeddingJobManualRetryEligibility.LIVE_JOB_EXISTS);
    }

    @Test
    @DisplayName("삭제된 문서는 최신 Version이어도 재처리를 차단한다")
    void evaluate_returnsDocumentDeleted_whenDocumentWasDeleted() {
        RetryTarget target = retryTarget();
        ReflectionTestUtils.setField(target.document(), "deletedAt", java.time.LocalDateTime.now());

        assertThat(policy.evaluate(
            target.job(),
            target.version(),
            target.document(),
            VERSION_ID,
            false
        )).isEqualTo(EmbeddingJobManualRetryEligibility.DOCUMENT_DELETED);
    }

    @Test
    @DisplayName("FAILED가 아닌 Job은 재처리 대상이 아니다")
    void evaluate_returnsJobNotFailed_whenJobIsNotFailed() {
        RetryTarget target = retryTarget();
        ReflectionTestUtils.setField(target.job(), "status", EmbeddingJobStatus.INDEXED);

        assertThat(policy.evaluate(
            target.job(),
            target.version(),
            target.document(),
            VERSION_ID,
            false
        )).isEqualTo(EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED);
    }

    private RetryTarget retryTarget() {
        Document document = Document.builder()
            .title("재처리 정책 테스트")
            .documentType(DocumentType.PDF)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.FAILED)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        DocumentVersion version = DocumentVersion.builder()
            .document(document)
            .versionNo(2)
            .status(DocumentVersionStatus.FAILED)
            .build();
        ReflectionTestUtils.setField(version, "id", VERSION_ID);
        document.updateCurrentVersion(version);
        EmbeddingJob job = EmbeddingJob.builder()
            .documentVersion(version)
            .status(EmbeddingJobStatus.FAILED)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(job, "id", 10L);
        return new RetryTarget(job, version, document);
    }

    /**
     * 한 판정에서 사용하는 Job·Version·Document 테스트 묶음이다.
     */
    private record RetryTarget(
        EmbeddingJob job,
        DocumentVersion version,
        Document document
    ) {
    }
}
