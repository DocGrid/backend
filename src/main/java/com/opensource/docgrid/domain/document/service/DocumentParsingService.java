package com.opensource.docgrid.domain.document.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.PreparationResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 두 개의 짧은 DB Transaction 사이에서 원본 읽기, Text 파싱과 Chunk 계산을 조정한다.
 *
 * <p>이 Service 자체에는 Transaction을 적용하지 않아 MinIO I/O와 CPU 계산 중 DB 행 잠금이
 * 유지되지 않게 한다. 외부 구간에는 불변 Snapshot과 Draft만 전달하고 JPA Entity는 전달하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DocumentParsingService {

    private final DocumentChunkTransactionService transactionService;
    private final FileStorageService fileStorageService;
    private final TextDocumentParser textDocumentParser;
    private final FixedSizeChunker fixedSizeChunker;

    /**
     * 현재 Attempt가 소유한 Job의 원본을 Chunking하고 저장하거나 기존 결과를 재생한다.
     *
     * @param jobId 현재 Embedding Job 식별자
     * @param attemptId 현재 실행 Attempt 식별자
     * @param request Worker와 Claim Token
     * @return API 응답과 이번 호출의 신규 저장 여부
     */
    public ChunkResult createChunks(
        Long jobId,
        Long attemptId,
        CreateDocumentChunksRequest request
    ) {
        // 1. 준비 Transaction에서 소유권과 상태를 검증하고 외부 작업용 Snapshot을 만든다.
        PreparationResult preparation = transactionService.prepare(
            jobId,
            attemptId,
            request.workerId(),
            request.claimToken()
        );

        // 2. 이미 CHUNKED인 유효한 재호출은 Object Storage를 다시 읽지 않고 즉시 반환한다.
        if (preparation.replayResult() != null) {
            return preparation.replayResult();
        }

        // 3. Transaction 밖에서 MinIO 읽기, 엄격한 UTF-8 파싱과 결정적 Chunk 계산을 수행한다.
        byte[] content = fileStorageService.read(preparation.fileSnapshot().storedFile());
        String canonicalText = textDocumentParser.parse(content);
        List<DocumentChunkDraft> drafts = fixedSizeChunker.chunk(canonicalText);
        if (drafts.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }

        // 4. 완료 Transaction이 소유권을 다시 검증하고 Chunk Set과 상태·이벤트를 원자적으로 저장한다.
        return transactionService.complete(
            jobId,
            attemptId,
            request.workerId(),
            request.claimToken(),
            preparation.fileSnapshot().documentVersionId(),
            drafts
        );
    }
}
