package com.opensource.docgrid.domain.embedding.service.command;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 잠긴 Embedding Job의 현재 Worker·Claim Token·Lease 소유권 정책을 검증하는 Component.
 *
 * <p>Repository 조회와 Transaction 시작은 담당하지 않는다. 호출자가 Job 행 잠금을 획득한 뒤 전달한
 * 현재 시각을 기준으로 소유권만 검증해 Attempt 시작과 후속 인덱싱 단계가 같은 정책을 재사용하게 한다.
 */
@Slf4j
@Component
public class EmbeddingJobOwnershipValidator {

    /**
     * 잠긴 Job이 요청 Worker와 Claim Token에 속하고 Lease가 유효한지 검증한다.
     *
     * @param embeddingJob 쓰기 잠금을 획득한 Embedding Job
     * @param workerId 소유권을 주장하는 Worker 식별자
     * @param claimToken 현재 Claim을 증명하는 Token
     * @param validatedAt Job 잠금 획득 후 계산한 검증 시각
     */
    public void validate(
        EmbeddingJob embeddingJob,
        Long workerId,
        String claimToken,
        LocalDateTime validatedAt
    ) {
        // 1. 소유권 필드는 PROCESSING 상태에서만 유효하므로 다른 상태의 실행 요청을 먼저 차단한다.
        if (embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING);
        }

        // 2. PROCESSING 상태와 소유권 필드가 함께 기록되지 않은 내부 데이터 모순을 구분한다.
        if (embeddingJob.getLockedByWorker() == null
            || !StringUtils.hasText(embeddingJob.getClaimToken())
            || embeddingJob.getLockedAt() == null
            || embeddingJob.getLockExpiresAt() == null) {
            log.error(
                "PROCESSING Embedding Job의 소유권 데이터가 불완전합니다. jobId={}, requestWorkerId={}",
                embeddingJob.getId(),
                workerId
            );
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        }

        // 3. Worker와 Token을 함께 비교해 과거 Claim 세대나 다른 Worker의 요청을 거부한다.
        if (!workerId.equals(embeddingJob.getLockedByWorker().getId())
            || !claimToken.equals(embeddingJob.getClaimToken())) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        }

        // 4. Lease 만료 시각과 정확히 같은 순간부터 현재 소유권을 만료로 처리한다.
        if (!embeddingJob.getLockExpiresAt().isAfter(validatedAt)) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED);
        }
    }
}
