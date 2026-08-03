package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.request.RenewEmbeddingJobLeaseRequest;
import com.opensource.docgrid.domain.embedding.dto.response.RenewedEmbeddingJobLeaseResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * EmbeddingJobLeaseService의 Job → Worker 잠금, 소유권·생존 검증과 만료 시각 갱신을 검증한다.
 *
 * <p>실제 DB 잠금은 통합 테스트에 맡기고, 단위 경계에서는 같은 기준 시각과 잠금 호출 순서, 실패 시
 * Entity 변경 및 후속 조회 차단을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobLeaseService 테스트")
class EmbeddingJobLeaseServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 15, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-03T06:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private WorkerNodeRepository workerNodeRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;
    @Mock private EmbeddingJobConverter embeddingJobConverter;

    private IndexingWorkerProperties workerProperties;
    private EmbeddingJobLeaseService leaseService;

    @BeforeEach
    void setUp() {
        workerProperties = new IndexingWorkerProperties();
        workerProperties.setLeaseDuration(Duration.ofMinutes(5));
        workerProperties.setDeadThreshold(Duration.ofSeconds(30));
        leaseService = new EmbeddingJobLeaseService(
            embeddingJobRepository,
            workerNodeRepository,
            ownershipValidator,
            embeddingJobConverter,
            workerProperties,
            FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("현재 소유권과 Worker Heartbeat가 유효하면 Lease 만료 시각을 갱신한다")
    void renew_extendsLease_when_currentOwnershipAndWorkerAreValid() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW);
        EmbeddingJob embeddingJob = createOwnedJob(workerNode, NOW.plusMinutes(1));
        RenewEmbeddingJobLeaseRequest request = new RenewEmbeddingJobLeaseRequest(WORKER_ID, CLAIM_TOKEN);
        RenewedEmbeddingJobLeaseResponse expected = new RenewedEmbeddingJobLeaseResponse(
            JOB_ID,
            WORKER_ID,
            NOW,
            NOW.plusMinutes(5)
        );
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(workerNodeRepository.findByIdForUpdate(WORKER_ID)).willReturn(Optional.of(workerNode));
        given(embeddingJobConverter.toRenewedLeaseResponse(embeddingJob, NOW)).willReturn(expected);

        RenewedEmbeddingJobLeaseResponse result = leaseService.renew(JOB_ID, request);

        InOrder lockOrder = inOrder(embeddingJobRepository, workerNodeRepository);
        lockOrder.verify(embeddingJobRepository).findByIdForUpdate(JOB_ID);
        lockOrder.verify(workerNodeRepository).findByIdForUpdate(WORKER_ID);
        then(ownershipValidator).should().validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, NOW);
        assertThat(result).isEqualTo(expected);
        assertThat(embeddingJob.getLockExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(result.toString()).doesNotContain(CLAIM_TOKEN);
    }

    @Test
    @DisplayName("Heartbeat가 DEAD 경계에 도달한 Worker는 Lease를 갱신할 수 없다")
    void renew_throws_when_workerHeartbeatIsExpired() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW.minusSeconds(30));
        EmbeddingJob embeddingJob = createOwnedJob(workerNode, NOW.plusMinutes(1));
        LocalDateTime originalExpiresAt = embeddingJob.getLockExpiresAt();
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(workerNodeRepository.findByIdForUpdate(WORKER_ID)).willReturn(Optional.of(workerNode));

        assertThatThrownBy(() -> leaseService.renew(
            JOB_ID,
            new RenewEmbeddingJobLeaseRequest(WORKER_ID, CLAIM_TOKEN)
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WORKER_NOT_AVAILABLE);
        assertThat(embeddingJob.getLockExpiresAt()).isEqualTo(originalExpiresAt);
        then(embeddingJobConverter).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("소유권 검증이 실패하면 Worker 행을 잠그지 않는다")
    void renew_doesNotLockWorker_when_ownershipIsInvalid() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW);
        EmbeddingJob embeddingJob = createOwnedJob(workerNode, NOW.plusMinutes(1));
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        org.mockito.BDDMockito.willThrow(
            new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID)
        ).given(ownershipValidator).validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, NOW);

        assertThatThrownBy(() -> leaseService.renew(
            JOB_ID,
            new RenewEmbeddingJobLeaseRequest(WORKER_ID, CLAIM_TOKEN)
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        then(workerNodeRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Job이 없으면 정의된 Not Found 오류를 반환한다")
    void renew_throws_when_jobDoesNotExist() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> leaseService.renew(
            JOB_ID,
            new RenewEmbeddingJobLeaseRequest(WORKER_ID, CLAIM_TOKEN)
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_FOUND);
        then(ownershipValidator).shouldHaveNoInteractions();
        then(workerNodeRepository).shouldHaveNoInteractions();
    }

    private EmbeddingJob createOwnedJob(WorkerNode workerNode, LocalDateTime expiresAt) {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        embeddingJob.claim(workerNode, CLAIM_TOKEN, NOW.minusMinutes(1), expiresAt);
        return embeddingJob;
    }

    private WorkerNode createWorker(WorkerStatus status, LocalDateTime heartbeatAt) {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName("lease-worker")
            .instanceId("lease-worker-instance")
            .status(status)
            .lastHeartbeatAt(heartbeatAt)
            .startedAt(NOW.minusMinutes(10))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);
        return workerNode;
    }
}
