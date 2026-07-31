package com.opensource.docgrid.domain.embedding.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 준비 Snapshot의 Chunk를 순서대로 외부 서버에 전달하고 검증된 Vector Draft를 생성한다.
 *
 * <p>DB Transaction과 JPA Entity를 사용하지 않으며, 한 번에 한 Chunk만 호출해 실패 시 어떤 결과도
 * 저장되지 않게 한다. 반환 Vector는 Model 차원과 유한 값을 검증한 뒤 SHA-256 Hash와 함께 복사한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingGenerator {

    private final EmbeddingClient embeddingClient;

    /**
     * 정렬된 Chunk Snapshot을 단건 순차 호출해 같은 순서의 Embedding Draft로 변환한다.
     */
    public List<DocumentEmbeddingDraft> generate(EmbeddingWork work) {
        validateWork(work);

        List<DocumentEmbeddingDraft> drafts = new ArrayList<>(work.chunks().size());
        for (ChunkSnapshot chunk : work.chunks()) {
            // 1. 현재 Chunk Text만 외부 서버로 보내 DB Transaction 없이 Vector를 생성한다.
            float[] vector = embeddingClient.embed(chunk.chunkText());

            // 2. Job 고정 Model의 차원과 모든 원소의 유한성을 저장 전에 검증한다.
            validateVector(vector, work.dimension());

            // 3. 검증된 Vector와 원본 Chunk Snapshot을 결합해 완료 Transaction용 Draft를 만든다.
            drafts.add(new DocumentEmbeddingDraft(
                chunk.chunkId(),
                chunk.chunkIndex(),
                chunk.contentHash(),
                vector,
                calculateVectorHash(vector)
            ));
        }
        return List.copyOf(drafts);
    }

    private void validateWork(EmbeddingWork work) {
        if (work == null
            || work.documentVersionId() == null
            || work.embeddingModelId() == null
            || work.dimension() <= 0
            || work.chunks() == null
            || work.chunks().isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
    }

    private void validateVector(float[] vector, int expectedDimension) {
        if (vector == null || vector.length != expectedDimension) {
            throw new DocGridException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID);
            }
        }
    }

    private String calculateVectorHash(float[] vector) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * Float.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
            for (float value : vector) {
                buffer.putFloat(value);
            }
            return HexFormat.of().formatHex(digest.digest(buffer.array()));
        } catch (NoSuchAlgorithmException exception) {
            throw new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID, exception);
        }
    }
}
