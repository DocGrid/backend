package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.PreparationResult;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Chunk Embedding 준비 Transaction의 잠금 이후 검증, 상태 전이, 재개와 완료 재생 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentEmbeddingTransactionService 테스트")
class DocumentEmbeddingTransactionServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long VERSION_ID = 5L;
    private static final Long MODEL_ID = 7L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 12, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;

    private DocumentEmbeddingTransactionService service;
    private EmbeddingJob embeddingJob;
    private DocumentVersion documentVersion;
    private EmbeddingModel embeddingModel;
    private EmbeddingJobAttempt attempt;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-29T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new DocumentEmbeddingTransactionService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            documentVersionRepository,
            documentChunkRepository,
            embeddingRepository,
            indexingEventRepository,
            ownershipValidator,
            clock
        );
        prepareEntities(DocumentVersionStatus.CHUNKED);
    }

    @Test
    @DisplayName("CHUNKED Version을 EMBEDDING으로 전환하고 고정 Model의 Chunk Snapshot을 만든다")
    void prepare_marksEmbeddingAndReturnsSnapshot() {
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.work().documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.work().embeddingModelId()).isEqualTo(MODEL_ID);
        assertThat(result.work().dimension()).isEqualTo(EmbeddingModelFixture.DIMENSION);
        assertThat(result.work().chunks())
            .extracting(chunk -> chunk.chunkIndex() + ":" + chunk.chunkText())
            .containsExactly("0:첫 번째", "1:두 번째");
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        then(ownershipValidator).should().validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, NOW);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(IndexingEventType.EMBEDDING_STARTED);
    }

    @Test
    @DisplayName("저장 결과가 없는 EMBEDDING Version은 시작 이벤트 없이 작업을 재개한다")
    void prepare_resumesEmbeddingWithoutDuplicateEvent() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "본문")));

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.work().chunks()).hasSize(1);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("모든 Chunk의 Embedding이 저장됐으면 기존 완료 결과를 재생한다")
    void prepare_replaysCompletedEmbeddings() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(2L);

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isTrue();
        assertThat(result.work()).isNull();
        assertThat(result.replayResult().created()).isFalse();
        assertThat(result.replayResult().embeddingCount()).isEqualTo(2);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("일부 Chunk의 Embedding만 저장된 상태는 내부 데이터 모순으로 거부한다")
    void prepare_rejectsPartialEmbeddings() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(1L);

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));
    }

    @Test
    @DisplayName("현재 Claim과 일치하지 않는 Attempt는 Version 조회 전에 거부한다")
    void prepare_rejectsInvalidAttempt() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        then(documentVersionRepository).shouldHaveNoInteractions();
        then(documentChunkRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Chunk Index가 연속되지 않으면 외부 호출 Snapshot을 만들지 않는다")
    void prepare_rejectsNonSequentialChunks() {
        givenValidContext(List.of(chunk(1, "본문")));

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT));

        then(embeddingRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("CHUNKED나 EMBEDDING이 아닌 Version은 Embedding 준비를 거부한다")
    void prepare_rejectsUnexpectedVersionStatus() {
        prepareEntities(DocumentVersionStatus.INDEXED);
        givenValidContext(List.of(chunk(0, "본문")));

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED));
    }

    private void givenValidContext(List<DocumentChunk> chunks) {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID)).willReturn(Optional.of(documentVersion));
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(VERSION_ID))
            .willReturn(chunks);
    }

    private void prepareEntities(DocumentVersionStatus versionStatus) {
        WorkerNode worker = WorkerNode.builder()
            .workerName("worker")
            .instanceId("instance")
            .status(WorkerStatus.ACTIVE)
            .startedAt(NOW.minusHours(1))
            .build();
        ReflectionTestUtils.setField(worker, "id", WORKER_ID);

        Document document = Document.builder()
            .title("문서")
            .documentType(DocumentType.TXT)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.INDEXING)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", 3L);

        documentVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(versionStatus)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);

        embeddingModel = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(embeddingModel, "id", MODEL_ID);

        embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .embeddingModel(embeddingModel)
            .status(EmbeddingJobStatus.PROCESSING)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);

        attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(worker)
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .status(AttemptStatus.STARTED)
            .startedAt(NOW.minusMinutes(1))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
    }

    private DocumentChunk chunk(int index, String text) {
        DocumentChunk chunk = DocumentChunk.builder()
            .documentVersion(documentVersion)
            .chunkIndex(index)
            .chunkText(text)
            .tokenCount(1)
            .charStart(index * 10)
            .charEnd(index * 10 + text.length())
            .contentHash(CONTENT_HASH)
            .build();
        ReflectionTestUtils.setField(chunk, "id", 20L + index);
        return chunk;
    }
}
