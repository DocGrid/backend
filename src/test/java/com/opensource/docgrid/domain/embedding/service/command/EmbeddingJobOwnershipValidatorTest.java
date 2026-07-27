package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Embedding Job의 공통 Worker·Claim Token·Lease 소유권 정책을 검증하는 단위 테스트.
 *
 * <p>Repository와 Transaction 없이 잠긴 Job을 가정하고, 상태와 소유권 필드 불변식부터 Worker·Token
 * 일치 및 Lease의 정확한 만료 경계까지 기존 Attempt 시작 계약과 같은 오류 코드로 고정한다.
 */
@DisplayName("EmbeddingJobOwnershipValidator 테스트")
class EmbeddingJobOwnershipValidatorTest {

    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final Long OTHER_WORKER_ID = 2L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String STALE_CLAIM_TOKEN = "8d242ac5-0916-4e1c-a781-1f7b932f989b";
    private static final LocalDateTime VALIDATED_AT = LocalDateTime.of(2026, 7, 27, 18, 30);

    private final EmbeddingJobOwnershipValidator validator = new EmbeddingJobOwnershipValidator();

    @Test
    @DisplayName("현재 Worker와 Token이 일치하고 Lease가 남은 PROCESSING Job은 통과한다")
    void validate_passes_when_currentOwnershipAndLeaseAreValid() {
        EmbeddingJob embeddingJob = createOwnedJob(VALIDATED_AT.plusMinutes(5));

        assertThatCode(() -> validator.validate(
            embeddingJob,
            WORKER_ID,
            CLAIM_TOKEN,
            VALIDATED_AT
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} 상태는 소유권 검증을 통과할 수 없다")
    @EnumSource(
        value = EmbeddingJobStatus.class,
        names = {"PENDING", "INDEXED", "FAILED", "CANCELED"}
    )
    @DisplayName("PROCESSING이 아닌 Job은 실행할 수 없는 상태 오류다")
    void validate_throws_when_jobIsNotProcessing(EmbeddingJobStatus status) {
        EmbeddingJob embeddingJob = createJob(status);

        assertThatThrownBy(() -> validator.validate(
            embeddingJob,
            WORKER_ID,
            CLAIM_TOKEN,
            VALIDATED_AT
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_PROCESSING);
    }

    @ParameterizedTest(name = "{0} 필드가 없으면 소유권 불변식 오류")
    @MethodSource("incompleteOwnershipFields")
    @DisplayName("PROCESSING Job의 소유권 필드가 불완전하면 서버 오류가 발생한다")
    void validate_throws_when_processingOwnershipIsIncomplete(String fieldName) {
        EmbeddingJob embeddingJob = createOwnedJob(VALIDATED_AT.plusMinutes(5));
        ReflectionTestUtils.setField(embeddingJob, fieldName, null);

        assertThatThrownBy(() -> validator.validate(
            embeddingJob,
            WORKER_ID,
            CLAIM_TOKEN,
            VALIDATED_AT
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT
            );
    }

    @Test
    @DisplayName("요청 Worker가 현재 소유 Worker와 다르면 소유권 오류가 발생한다")
    void validate_throws_when_workerDoesNotOwnJob() {
        EmbeddingJob embeddingJob = createOwnedJob(VALIDATED_AT.plusMinutes(5));

        assertThatThrownBy(() -> validator.validate(
            embeddingJob,
            OTHER_WORKER_ID,
            CLAIM_TOKEN,
            VALIDATED_AT
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
    }

    @Test
    @DisplayName("요청 Claim Token이 현재 Token과 다르면 소유권 오류가 발생한다")
    void validate_throws_when_claimTokenIsStale() {
        EmbeddingJob embeddingJob = createOwnedJob(VALIDATED_AT.plusMinutes(5));

        assertThatThrownBy(() -> validator.validate(
            embeddingJob,
            WORKER_ID,
            STALE_CLAIM_TOKEN,
            VALIDATED_AT
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
    }

    @ParameterizedTest(name = "Lease 만료 시각 {0}")
    @MethodSource("expiredLeaseTimes")
    @DisplayName("Lease 만료 시각과 같거나 지난 요청은 거부한다")
    void validate_throws_when_leaseIsExpired(LocalDateTime lockExpiresAt) {
        EmbeddingJob embeddingJob = createOwnedJob(lockExpiresAt);

        assertThatThrownBy(() -> validator.validate(
            embeddingJob,
            WORKER_ID,
            CLAIM_TOKEN,
            VALIDATED_AT
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED);
    }

    private static Stream<Arguments> incompleteOwnershipFields() {
        return Stream.of(
            Arguments.of("lockedByWorker"),
            Arguments.of("claimToken"),
            Arguments.of("lockedAt"),
            Arguments.of("lockExpiresAt")
        );
    }

    private static Stream<Arguments> expiredLeaseTimes() {
        return Stream.of(
            Arguments.of(VALIDATED_AT),
            Arguments.of(VALIDATED_AT.minusNanos(1))
        );
    }

    private EmbeddingJob createOwnedJob(LocalDateTime lockExpiresAt) {
        EmbeddingJob embeddingJob = createJob(EmbeddingJobStatus.PENDING);
        embeddingJob.claim(
            createWorker(WORKER_ID),
            CLAIM_TOKEN,
            VALIDATED_AT.minusMinutes(1),
            lockExpiresAt
        );
        return embeddingJob;
    }

    private EmbeddingJob createJob(EmbeddingJobStatus status) {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(status)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        return embeddingJob;
    }

    private WorkerNode createWorker(Long workerId) {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName("ownership-worker-" + workerId)
            .instanceId("ownership-worker-instance-" + workerId)
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(VALIDATED_AT)
            .startedAt(VALIDATED_AT.minusMinutes(2))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", workerId);
        return workerNode;
    }
}
