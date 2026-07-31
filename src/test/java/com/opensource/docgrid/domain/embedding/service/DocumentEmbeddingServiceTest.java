package com.opensource.docgrid.domain.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentEmbeddingsRequest;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService.EmbeddingResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.CompletionResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.PreparationResult;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 준비·외부 Vector 생성·완료 Transaction의 실행 순서와 재생·실패 중단 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentEmbeddingService 테스트")
class DocumentEmbeddingServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long VERSION_ID = 5L;
    private static final Long MODEL_ID = 7L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Mock private DocumentEmbeddingTransactionService transactionService;
    @Mock private DocumentEmbeddingGenerator embeddingGenerator;

    private DocumentEmbeddingService service;
    private CreateDocumentEmbeddingsRequest request;
    private EmbeddingWork work;

    @BeforeEach
    void setUp() {
        service = new DocumentEmbeddingService(transactionService, embeddingGenerator);
        request = new CreateDocumentEmbeddingsRequest(WORKER_ID, CLAIM_TOKEN);
        work = new EmbeddingWork(
            VERSION_ID,
            MODEL_ID,
            2,
            List.of(new ChunkSnapshot(20L, 0, "본문", CONTENT_HASH))
        );
    }

    @Test
    @DisplayName("준비, Vector 생성, 완료 순서로 실행하고 최초 저장 결과를 반환한다")
    void createEmbeddings_executesExternalWorkBetweenTransactions() {
        List<DocumentEmbeddingDraft> drafts = List.of(draft());
        CompletionResult completion = completion(true);
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(work, null));
        given(embeddingGenerator.generate(work)).willReturn(drafts);
        given(transactionService.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work,
            drafts
        )).willReturn(completion);

        EmbeddingResult result = service.createEmbeddings(JOB_ID, ATTEMPT_ID, request);

        assertThat(result.created()).isTrue();
        assertThat(result.response().documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.response().embeddingModelId()).isEqualTo(MODEL_ID);
        assertThat(result.response().embeddingCount()).isOne();
        assertThat(result.response().versionStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);

        InOrder order = inOrder(transactionService, embeddingGenerator);
        order.verify(transactionService).prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);
        order.verify(embeddingGenerator).generate(work);
        order.verify(transactionService).complete(
            JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN, work, drafts
        );
    }

    @Test
    @DisplayName("준비 단계가 완료 결과를 반환하면 외부 호출과 완료 Transaction 없이 재생한다")
    void createEmbeddings_replaysWithoutExternalWork() {
        CompletionResult replay = completion(false);
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(null, replay));

        EmbeddingResult result = service.createEmbeddings(JOB_ID, ATTEMPT_ID, request);

        assertThat(result.created()).isFalse();
        assertThat(result.response().embeddingCount()).isOne();
        then(embeddingGenerator).shouldHaveNoInteractions();
        then(transactionService).should(never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("외부 Vector 생성이 실패하면 완료 Transaction을 호출하지 않는다")
    void createEmbeddings_stopsWhenGeneratorFails() {
        given(transactionService.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .willReturn(new PreparationResult(work, null));
        given(embeddingGenerator.generate(work))
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE));

        assertThatThrownBy(() -> service.createEmbeddings(JOB_ID, ATTEMPT_ID, request))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE));

        then(transactionService).should(never()).complete(any(), any(), any(), any(), any(), any());
    }

    private DocumentEmbeddingDraft draft() {
        float[] vector = {0.1f, 0.2f};
        return new DocumentEmbeddingDraft(
            20L,
            0,
            CONTENT_HASH,
            vector,
            EmbeddingVectorSupport.calculateHash(vector)
        );
    }

    private CompletionResult completion(boolean created) {
        return new CompletionResult(
            JOB_ID,
            ATTEMPT_ID,
            VERSION_ID,
            MODEL_ID,
            1,
            1,
            DocumentVersionStatus.EMBEDDING,
            created
        );
    }
}
