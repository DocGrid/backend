package com.opensource.docgrid.domain.embedding.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;

/**
 * Embedding Job Attempt Entity를 시작 또는 실패 결과 API DTO로 변환하는 Converter.
 *
 * <p>Transaction 안에서 LAZY Job·Worker의 식별자만 추출하고 Entity, Claim Token, 내부 오류 정보는
 * Controller 경계 밖으로 노출하지 않는다.
 */
@Component
public class EmbeddingJobAttemptConverter {

    public StartedEmbeddingJobAttemptResponse toStartedResponse(EmbeddingJobAttempt embeddingJobAttempt) {
        // 소유권 Token 대신 후속 실행에 필요한 안정적인 식별자와 시작 상태만 반환한다.
        return new StartedEmbeddingJobAttemptResponse(
            embeddingJobAttempt.getId(),
            embeddingJobAttempt.getEmbeddingJob().getId(),
            embeddingJobAttempt.getAttemptNo(),
            embeddingJobAttempt.getWorkerNode().getId(),
            embeddingJobAttempt.getStatus(),
            embeddingJobAttempt.getStartedAt()
        );
    }

    public DocumentIndexingFailureResponse toFailureResponse(EmbeddingJobAttempt embeddingJobAttempt) {
        // 실패 유형은 서버가 저장한 제한된 Enum 이름만 해석하며 자유 형식 오류 메시지는 노출하지 않는다.
        return new DocumentIndexingFailureResponse(
            embeddingJobAttempt.getEmbeddingJob().getId(),
            embeddingJobAttempt.getId(),
            embeddingJobAttempt.getAttemptNo(),
            embeddingJobAttempt.getStatus(),
            IndexingFailureType.valueOf(embeddingJobAttempt.getErrorCode()),
            embeddingJobAttempt.getEndedAt(),
            embeddingJobAttempt.getDurationMs()
        );
    }
}
