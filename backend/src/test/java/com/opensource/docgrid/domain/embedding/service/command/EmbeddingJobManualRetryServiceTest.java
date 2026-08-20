package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * EmbeddingJobManualRetryService의 대상 검증, 재개 지점 결정과 소유권 초기화를 검증한다.
 *
 * <p>Repository는 이미 잠긴 Job, Version, Document를 반환한다고 가정하고, 최종 실패가 아닌 Job과
 * 재처리 조건을 만족하지 않는 문서가 어떤 상태 변경이나 Embedding 삭제도 실행하지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobManualRetryService 테스트")
class EmbeddingJobManualRetryServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long DOCUMENT_ID = 3L;
    private static final Long VERSION_ID = 5L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime REQUEUED_AT = LocalDateTime.of(2026, 8, 6, 15, 0);
    private static final LocalDateTime FAILED_AT = REQUEUED_AT.minusHours(1);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IndexingEventRepository indexingEventRepository;

    private EmbeddingJobManualRetryService manualRetryService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            REQUEUED_AT.atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault()
        );
        manualRetryService = new EmbeddingJobManualRetryService(
            embeddingJobRepository,
            documentVersionRepository,
            documentRepository,
            documentChunkRepository,
            embeddingRepository,
            indexingEventRepository,
            new EmbeddingJobConverter(),
            new EmbeddingJobManualRetryPolicy(),
            clock
        );
    }

    @Test
    @DisplayName("Chunk가 남아 있는 최종 실패 Job은 파싱을 생략하는 CHUNKED 지점으로 재개한다")
    void retry_requeuesJobAndResumesFromChunked_when_chunksExist() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        givenLockedTarget(embeddingJob, documentVersion, document);
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);
        given(embeddingRepository.deleteByDocumentVersionId(VERSION_ID)).willReturn(4);

        ManualRetriedIndexingJobResponse response = manualRetryService.retry(JOB_ID);

        assertThat(response.jobId()).isEqualTo(JOB_ID);
        assertThat(response.status()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(response.documentVersionStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);
        assertThat(response.retryCount()).isEqualTo(3);
        assertThat(response.maxRetryCount()).isEqualTo(3);
        assertThat(response.requeuedAt()).isEqualTo(REQUEUED_AT);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXING);
    }

    @Test
    @DisplayName("Chunk가 없는 최종 실패 Job은 파싱부터 다시 시작하는 UPLOADED 지점으로 재개한다")
    void retry_resumesFromUploaded_when_chunksDoNotExist() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        givenLockedTarget(embeddingJob, documentVersion, document);
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(false);
        given(embeddingRepository.deleteByDocumentVersionId(VERSION_ID)).willReturn(0);

        ManualRetriedIndexingJobResponse response = manualRetryService.retry(JOB_ID);

        assertThat(response.documentVersionStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
    }

    @Test
    @DisplayName("재처리한 Job은 즉시 Claim 가능하도록 소유권과 종료 시각을 모두 초기화한다")
    void retry_clearsOwnershipAndKeepsRetryHistory() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        givenLockedTarget(embeddingJob, documentVersion, document);
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);

        manualRetryService.retry(JOB_ID);

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(embeddingJob.getLockedByWorker()).isNull();
        assertThat(embeddingJob.getClaimToken()).isNull();
        assertThat(embeddingJob.getLockedAt()).isNull();
        assertThat(embeddingJob.getLockExpiresAt()).isNull();
        assertThat(embeddingJob.getFailedAt()).isNull();
        assertThat(embeddingJob.getNextRetryAt()).isNull();
        // Retry 이력은 감사 대상이므로 삭제하지 않는다. 자동 재시도 여유를 남기지 않으므로 이번
        // 재처리 실행이 다시 실패하면 재예약 없이 곧바로 최종 실패로 종결된다.
        assertThat(embeddingJob.getRetryCount()).isEqualTo(3);
        assertThat(embeddingJob.hasRemainingRetries()).isFalse();
    }

    @Test
    @DisplayName("수동 재처리는 Claim Token 없는 감사 Event를 같은 시각으로 기록한다")
    void retry_savesManualRetryEventWithoutClaimToken() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        givenLockedTarget(embeddingJob, documentVersion, document);
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);
        given(embeddingRepository.deleteByDocumentVersionId(VERSION_ID)).willReturn(4);

        manualRetryService.retry(JOB_ID);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        IndexingEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(IndexingEventType.MANUAL_RETRY);
        assertThat(event.getFromStatus()).isEqualTo(EmbeddingJobStatus.FAILED.name());
        assertThat(event.getToStatus()).isEqualTo(EmbeddingJobStatus.PENDING.name());
        assertThat(event.getOccurredAt()).isEqualTo(REQUEUED_AT);
        assertThat(event.getMetadataJson())
            .contains("\"resumeVersionStatus\":\"CHUNKED\"")
            .contains("\"retryCount\":3", "\"maxRetryCount\":3", "\"deletedEmbeddingCount\":4")
            .doesNotContain(CLAIM_TOKEN);
    }

    @Test
    @DisplayName("현재 검색 대상인 이전 INDEXED Version이 있으면 문서 상태와 포인터를 보존한다")
    void retry_keepsIndexedDocument_when_previousSearchableVersionExists() {
        Document document = createDocument(DocumentStatus.INDEXED);
        DocumentVersion previousVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(DocumentVersionStatus.INDEXED)
            .build();
        ReflectionTestUtils.setField(previousVersion, "id", 4L);
        document.updateCurrentVersion(previousVersion);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        givenLockedTarget(embeddingJob, documentVersion, document);
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);

        manualRetryService.retry(JOB_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getCurrentVersion().getId()).isEqualTo(4L);
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
    }

    @Test
    @DisplayName("존재하지 않는 Job은 Not Found 예외가 발생한다")
    void retry_throws_when_jobDoesNotExist() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_FOUND);
        then(embeddingRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("자동 재시도가 예정된 PENDING Job은 수동 재처리를 거부한다")
    void retry_throws_when_jobIsPendingRetry() {
        Document document = createDocument(DocumentStatus.INDEXING);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        ReflectionTestUtils.setField(embeddingJob, "status", EmbeddingJobStatus.PENDING);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED
            );
        then(documentVersionRepository).shouldHaveNoInteractions();
        then(embeddingRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("처리 중인 PROCESSING Job은 수동 재처리를 거부한다")
    void retry_throws_when_jobIsProcessing() {
        Document document = createDocument(DocumentStatus.INDEXING);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        ReflectionTestUtils.setField(embeddingJob, "status", EmbeddingJobStatus.PROCESSING);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED
            );
        then(embeddingRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 완료된 INDEXED Job은 수동 재처리를 거부한다")
    void retry_throws_when_jobIsIndexed() {
        Document document = createDocument(DocumentStatus.INDEXED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        ReflectionTestUtils.setField(embeddingJob, "status", EmbeddingJobStatus.INDEXED);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED
            );
        then(embeddingRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("더 새로운 Version이 올라온 문서는 과거 Version 재처리를 거부한다")
    void retry_throws_when_targetIsNotLatestVersion() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        DocumentVersion newerVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(3)
            .status(DocumentVersionStatus.UPLOADED)
            .build();
        ReflectionTestUtils.setField(newerVersion, "id", 6L);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(newerVersion));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID
            );
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.FAILED);
        then(embeddingRepository).should(never()).deleteByDocumentVersionId(anyLong());
        then(indexingEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("삭제된 문서의 Job은 수동 재처리를 거부한다")
    void retry_throws_when_documentIsDeleted() {
        Document document = createDocument(DocumentStatus.FAILED);
        ReflectionTestUtils.setField(document, "deletedAt", FAILED_AT);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID
            );
        then(embeddingRepository).should(never()).deleteByDocumentVersionId(anyLong());
    }

    @Test
    @DisplayName("같은 Version에 살아 있는 Job이 있으면 중복 처리를 막기 위해 거부한다")
    void retry_throws_when_liveJobExistsForSameVersion() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(documentVersion));
        given(embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            eq(VERSION_ID),
            anyCollection()
        )).willReturn(1L);

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID
            );
        then(embeddingRepository).should(never()).deleteByDocumentVersionId(anyLong());
        then(indexingEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("최종 실패 Job의 Version이 FAILED가 아니면 종료 데이터 불변식 오류로 중단한다")
    void retry_throws_when_versionIsNotFailed() {
        Document document = createDocument(DocumentStatus.FAILED);
        DocumentVersion documentVersion = createFailedVersion(document);
        ReflectionTestUtils.setField(documentVersion, "status", DocumentVersionStatus.EMBEDDING);
        EmbeddingJob embeddingJob = createFailedJob(documentVersion);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> manualRetryService.retry(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
        then(embeddingRepository).should(never()).deleteByDocumentVersionId(anyLong());
    }

    private void givenLockedTarget(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(documentVersion));
    }

    private Document createDocument(DocumentStatus status) {
        Document document = Document.builder()
            .title("수동 재처리 테스트 문서")
            .documentType(DocumentType.PDF)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(status)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }

    private DocumentVersion createFailedVersion(Document document) {
        DocumentVersion documentVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(2)
            .status(DocumentVersionStatus.FAILED)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);
        if (document.getCurrentVersion() == null) {
            document.updateCurrentVersion(documentVersion);
        }
        return documentVersion;
    }

    /**
     * 자동 재시도를 모두 소진하고 소유권 정보가 남아 있는 최종 실패 Job을 만든다.
     */
    private EmbeddingJob createFailedJob(DocumentVersion documentVersion) {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName("manual-retry-worker")
            .instanceId("manual-retry-worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(FAILED_AT.minusMinutes(1))
            .startedAt(FAILED_AT.minusMinutes(10))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        ReflectionTestUtils.setField(embeddingJob, "retryCount", 3);
        embeddingJob.claim(workerNode, CLAIM_TOKEN, FAILED_AT.minusMinutes(5), FAILED_AT);
        embeddingJob.markFailed("EMBEDDING_SERVER_UNAVAILABLE", "임베딩 서버 장애", FAILED_AT);
        return embeddingJob;
    }
}
