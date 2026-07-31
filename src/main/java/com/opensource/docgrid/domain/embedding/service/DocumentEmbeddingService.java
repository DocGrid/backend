package com.opensource.docgrid.domain.embedding.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentEmbeddingsRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentEmbeddingsResponse;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.CompletionResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.PreparationResult;

import lombok.RequiredArgsConstructor;

/**
 * 두 개의 짧은 DB Transaction 사이에서 Chunk별 외부 Embedding 호출을 조정한다.
 *
 * <p>이 Service 자체에는 Transaction을 적용하지 않아 외부 HTTP 호출 중 DB 행 잠금이 유지되지 않게
 * 한다. 준비 Snapshot과 Vector Draft만 단계 사이에 전달하고 JPA Entity는 전달하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingService {

    private final DocumentEmbeddingTransactionService transactionService;
    private final DocumentEmbeddingGenerator embeddingGenerator;

    /**
     * 현재 Attempt가 소유한 Job의 Chunk Embedding을 생성·저장하거나 기존 결과를 재생한다.
     */
    public EmbeddingResult createEmbeddings(
        Long jobId,
        Long attemptId,
        CreateDocumentEmbeddingsRequest request
    ) {
        // 1. 준비 Transaction에서 소유권, Version·Model과 Chunk Set을 검증한다.
        PreparationResult preparation = transactionService.prepare(
            jobId,
            attemptId,
            request.workerId(),
            request.claimToken()
        );

        // 2. 같은 Model의 전체 결과가 이미 저장됐으면 외부 서버를 호출하지 않고 즉시 재생한다.
        if (preparation.isReplay()) {
            return result(preparation.replayResult());
        }

        // 3. Transaction 밖에서 Chunk를 순서대로 호출해 검증된 Vector Draft 전체를 만든다.
        List<DocumentEmbeddingDraft> drafts = embeddingGenerator.generate(preparation.work());

        // 4. 완료 Transaction이 소유권과 Snapshot을 다시 검증하고 전체 Set을 원자 저장한다.
        return result(transactionService.complete(
            jobId,
            attemptId,
            request.workerId(),
            request.claimToken(),
            preparation.work(),
            drafts
        ));
    }

    private EmbeddingResult result(CompletionResult completion) {
        return new EmbeddingResult(
            new DocumentEmbeddingsResponse(
                completion.jobId(),
                completion.attemptId(),
                completion.documentVersionId(),
                completion.embeddingModelId(),
                completion.chunkCount(),
                completion.embeddingCount(),
                completion.documentVersionStatus()
            ),
            completion.created()
        );
    }

    /**
     * Embedding Set 응답과 HTTP 생성·재생 상태를 Controller에 함께 전달한다.
     */
    public record EmbeddingResult(
        DocumentEmbeddingsResponse response,
        boolean created
    ) {
    }
}
