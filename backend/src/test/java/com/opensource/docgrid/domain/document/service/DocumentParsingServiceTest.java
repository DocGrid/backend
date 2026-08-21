package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.FileSnapshot;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.PreparationResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentChunksResponse;

/**
 * 두 DB Transaction 사이에서 Storage·Parser·Chunker를 호출하는 Orchestration 순서를 검증한다.
 *
 * <p>완료 재생과 외부 작업 실패에서는 불필요한 후속 단계가 실행되지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentParsingService 테스트")
class DocumentParsingServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long VERSION_ID = 5L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final StoredFile STORED_FILE = new StoredFile(
        StorageProvider.MINIO, "bucket", "source.txt"
    );

    @Mock private DocumentChunkTransactionService transactionService;
    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentParserRegistry documentParserRegistry;
    @Mock private FixedSizeChunker fixedSizeChunker;

    private DocumentParsingService service;
    private CreateDocumentChunksRequest request;

    @BeforeEach
    void setUp() {
        service = new DocumentParsingService(
            transactionService,
            fileStorageService,
            documentParserRegistry,
            fixedSizeChunker
        );
        request = new CreateDocumentChunksRequest(WORKER_ID, CLAIM_TOKEN);
    }

    @Test
    @DisplayName("준비, 원본 읽기, 파싱, Chunk 계산, 완료 순서로 실행한다")
    void createChunks_executesExternalWorkBetweenTransactions() {
        FileSnapshot snapshot = new FileSnapshot(VERSION_ID, DocumentType.TXT, STORED_FILE);
        List<DocumentChunkDraft> drafts = List.of(draft());
        ChunkResult expected = new ChunkResult(response(), true);
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(snapshot, null));
        byte[] content = "본문".getBytes(StandardCharsets.UTF_8);
        ParsedDocument parsedDocument = ParsedDocument.single("본문");
        given(fileStorageService.read(STORED_FILE)).willReturn(content);
        given(documentParserRegistry.parse(DocumentType.TXT, content)).willReturn(parsedDocument);
        given(fixedSizeChunker.chunk(parsedDocument)).willReturn(drafts);
        given(transactionService.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            VERSION_ID,
            drafts
        )).willReturn(expected);

        ChunkResult result = service.createChunks(JOB_ID, ATTEMPT_ID, request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(transactionService, fileStorageService, documentParserRegistry, fixedSizeChunker);
        order.verify(transactionService).prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);
        order.verify(fileStorageService).read(STORED_FILE);
        order.verify(documentParserRegistry).parse(DocumentType.TXT, content);
        order.verify(fixedSizeChunker).chunk(parsedDocument);
        order.verify(transactionService).complete(
            JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN, VERSION_ID, drafts
        );
    }

    @Test
    @DisplayName("준비 단계가 완료 결과를 반환하면 외부 작업 없이 재생한다")
    void createChunks_replaysWithoutExternalWork() {
        ChunkResult replay = new ChunkResult(response(), false);
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(null, replay));

        ChunkResult result = service.createChunks(JOB_ID, ATTEMPT_ID, request);

        assertThat(result).isSameAs(replay);
        then(fileStorageService).shouldHaveNoInteractions();
        then(documentParserRegistry).shouldHaveNoInteractions();
        then(fixedSizeChunker).shouldHaveNoInteractions();
        then(transactionService).should(never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Storage 읽기 실패 시 Parser와 완료 Transaction을 호출하지 않는다")
    void createChunks_stopsWhenStorageReadFails() {
        givenWorkPreparation();
        given(fileStorageService.read(STORED_FILE)).willThrow(new IllegalStateException("storage"));

        assertThatThrownBy(() -> service.createChunks(JOB_ID, ATTEMPT_ID, request))
            .isInstanceOf(IllegalStateException.class);

        then(documentParserRegistry).shouldHaveNoInteractions();
        then(fixedSizeChunker).shouldHaveNoInteractions();
        then(transactionService).should(never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Parser 실패 시 Chunker와 완료 Transaction을 호출하지 않는다")
    void createChunks_stopsWhenParserFails() {
        givenWorkPreparation();
        byte[] content = "본문".getBytes(StandardCharsets.UTF_8);
        given(fileStorageService.read(STORED_FILE)).willReturn(content);
        given(documentParserRegistry.parse(DocumentType.TXT, content))
            .willThrow(new IllegalArgumentException("parse"));

        assertThatThrownBy(() -> service.createChunks(JOB_ID, ATTEMPT_ID, request))
            .isInstanceOf(IllegalArgumentException.class);

        then(fixedSizeChunker).shouldHaveNoInteractions();
        then(transactionService).should(never()).complete(any(), any(), any(), any(), any(), any());
    }

    private void givenWorkPreparation() {
        FileSnapshot snapshot = new FileSnapshot(VERSION_ID, DocumentType.TXT, STORED_FILE);
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(snapshot, null));
    }

    private DocumentChunkDraft draft() {
        return new DocumentChunkDraft(
            0,
            "본문",
            1,
            0,
            2,
            null,
            null,
            "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c",
            null
        );
    }

    private DocumentChunksResponse response() {
        return new DocumentChunksResponse(
            JOB_ID,
            ATTEMPT_ID,
            VERSION_ID,
            1,
            DocumentVersionStatus.CHUNKED
        );
    }
}
