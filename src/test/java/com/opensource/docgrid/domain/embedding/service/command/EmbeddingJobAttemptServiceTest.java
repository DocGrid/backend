package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobAttemptConverter;
import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Embedding Job Attempt 시작의 소유권·Lease 검증, 멱등 재생과 번호 할당을 검증하는 단위 테스트.
 *
 * <p>고정 Clock과 Mock Repository로 Job 잠금 이후의 결정적 시각 경계 및 오류 경로의 무저장을
 * 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobAttemptService 테스트")
class EmbeddingJobAttemptServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Instant NOW_INSTANT = Instant.parse("2026-07-26T12:40:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 21, 40);
    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final Long ATTEMPT_ID = 100L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String OTHER_TOKEN = "8d242ac5-0916-4e1c-a781-1f7b932f989b";

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private EmbeddingJobAttemptConverter embeddingJobAttemptConverter;

    private EmbeddingJobAttemptService service;
    private StartEmbeddingJobAttemptRequest request;

    @BeforeEach
    void setUp() {
        service = new EmbeddingJobAttemptService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            embeddingJobAttemptConverter,
            Clock.fixed(NOW_INSTANT, ZONE_ID)
        );
        request = new StartEmbeddingJobAttemptRequest(WORKER_ID, CLAIM_TOKEN);
    }

    @Test
    @DisplayName("기존 Attempt가 없으면 첫 번호로 STARTED Attempt를 생성한다")
    void start_createsFirstAttempt_when_currentClaimIsValid() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        StartedEmbeddingJobAttemptResponse expected = createResponse(1);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.empty());
        given(embeddingJobAttemptRepository.findTopByEmbeddingJobIdOrderByAttemptNoDesc(JOB_ID))
            .willReturn(Optional.empty());
        given(embeddingJobAttemptRepository.save(any(EmbeddingJobAttempt.class)))
            .willAnswer(invocation -> {
                EmbeddingJobAttempt attempt = invocation.getArgument(0);
                ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
                return attempt;
            });
        given(embeddingJobAttemptConverter.toStartedResponse(any(EmbeddingJobAttempt.class))).willReturn(expected);

        StartResult result = service.start(JOB_ID, request);

        assertThat(result.created()).isTrue();
        assertThat(result.response()).isEqualTo(expected);
        ArgumentCaptor<EmbeddingJobAttempt> captor = ArgumentCaptor.forClass(EmbeddingJobAttempt.class);
        then(embeddingJobAttemptRepository).should().save(captor.capture());
        EmbeddingJobAttempt saved = captor.getValue();
        assertThat(saved.getEmbeddingJob()).isSameAs(embeddingJob);
        assertThat(saved.getWorkerNode()).isSameAs(embeddingJob.getLockedByWorker());
        assertThat(saved.getAttemptNo()).isEqualTo(1);
        assertThat(saved.getClaimToken()).isEqualTo(CLAIM_TOKEN);
        assertThat(saved.getStatus()).isEqualTo(AttemptStatus.STARTED);
        assertThat(saved.getStartedAt()).isEqualTo(NOW);

        InOrder order = inOrder(embeddingJobRepository, embeddingJobAttemptRepository);
        order.verify(embeddingJobRepository).findByIdForUpdate(JOB_ID);
        order.verify(embeddingJobAttemptRepository).findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN);
        order.verify(embeddingJobAttemptRepository).findTopByEmbeddingJobIdOrderByAttemptNoDesc(JOB_ID);
        order.verify(embeddingJobAttemptRepository).save(any(EmbeddingJobAttempt.class));
    }

    @Test
    @DisplayName("기존 최대 번호가 있으면 새 Claim Attempt에 다음 번호를 할당한다")
    void start_assignsNextAttemptNumber_when_previousAttemptExists() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        EmbeddingJobAttempt previousAttempt = createAttempt(embeddingJob, 3, OTHER_TOKEN, WORKER_ID);
        StartedEmbeddingJobAttemptResponse expected = createResponse(4);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.empty());
        given(embeddingJobAttemptRepository.findTopByEmbeddingJobIdOrderByAttemptNoDesc(JOB_ID))
            .willReturn(Optional.of(previousAttempt));
        given(embeddingJobAttemptRepository.save(any(EmbeddingJobAttempt.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
        given(embeddingJobAttemptConverter.toStartedResponse(any(EmbeddingJobAttempt.class))).willReturn(expected);

        StartResult result = service.start(JOB_ID, request);

        assertThat(result.created()).isTrue();
        ArgumentCaptor<EmbeddingJobAttempt> captor = ArgumentCaptor.forClass(EmbeddingJobAttempt.class);
        then(embeddingJobAttemptRepository).should().save(captor.capture());
        assertThat(captor.getValue().getAttemptNo()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 현재 Claim 재전송은 기존 Attempt를 반환하고 저장하지 않는다")
    void start_replaysExistingAttempt_when_sameCurrentClaimIsRetried() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        EmbeddingJobAttempt existingAttempt = createAttempt(embeddingJob, 1, CLAIM_TOKEN, WORKER_ID);
        StartedEmbeddingJobAttemptResponse expected = createResponse(1);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(existingAttempt));
        given(embeddingJobAttemptConverter.toStartedResponse(existingAttempt)).willReturn(expected);

        StartResult result = service.start(JOB_ID, request);

        assertThat(result.created()).isFalse();
        assertThat(result.response()).isEqualTo(expected);
        then(embeddingJobAttemptRepository).should(never())
            .findTopByEmbeddingJobIdOrderByAttemptNoDesc(any());
        then(embeddingJobAttemptRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("Job이 없으면 404 오류이고 Attempt Repository를 호출하지 않는다")
    void start_throws_when_jobDoesNotExist() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(JOB_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_FOUND);
        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "{0} 상태에서는 Attempt를 시작할 수 없다")
    @EnumSource(
        value = EmbeddingJobStatus.class,
        names = {"PENDING", "INDEXED", "FAILED", "CANCELED"}
    )
    @DisplayName("PROCESSING이 아닌 Job은 Attempt를 시작할 수 없다")
    void start_throws_when_jobIsNotProcessing(EmbeddingJobStatus status) {
        EmbeddingJob embeddingJob = createJob(status);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> service.start(JOB_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_PROCESSING);
        assertNoAttemptWrite();
    }

    @ParameterizedTest(name = "{0} 필드가 없으면 소유권 불변식 오류")
    @MethodSource("incompleteOwnershipFields")
    @DisplayName("PROCESSING Job의 소유권 필드가 불완전하면 서버 오류가 발생한다")
    void start_throws_when_processingOwnershipIsIncomplete(String fieldName) {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        ReflectionTestUtils.setField(embeddingJob, fieldName, null);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> service.start(JOB_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        assertNoAttemptWrite();
    }

    @Test
    @DisplayName("요청 Worker가 현재 소유 Worker와 다르면 소유권 오류가 발생한다")
    void start_throws_when_workerDoesNotOwnJob() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        StartEmbeddingJobAttemptRequest otherWorkerRequest =
            new StartEmbeddingJobAttemptRequest(2L, CLAIM_TOKEN);

        assertThatThrownBy(() -> service.start(JOB_ID, otherWorkerRequest))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        assertNoAttemptWrite();
    }

    @Test
    @DisplayName("요청 Claim Token이 현재 소유권과 다르면 소유권 오류가 발생한다")
    void start_throws_when_claimTokenIsStale() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        StartEmbeddingJobAttemptRequest staleRequest =
            new StartEmbeddingJobAttemptRequest(WORKER_ID, OTHER_TOKEN);

        assertThatThrownBy(() -> service.start(JOB_ID, staleRequest))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID);
        assertNoAttemptWrite();
    }

    @ParameterizedTest(name = "Lease 만료 시각 {0}")
    @MethodSource("expiredLeaseTimes")
    @DisplayName("Lease 만료 시각과 같거나 지난 요청은 거부한다")
    void start_throws_when_leaseIsExpired(LocalDateTime lockExpiresAt) {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, lockExpiresAt);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> service.start(JOB_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_LEASE_EXPIRED);
        assertNoAttemptWrite();
    }

    @Test
    @DisplayName("기존 Attempt의 Worker가 현재 요청과 다르면 서버 불변식 오류가 발생한다")
    void start_throws_when_replayedAttemptWorkerIsInconsistent() {
        EmbeddingJob embeddingJob = createOwnedJob(CLAIM_TOKEN, NOW.plusMinutes(5));
        EmbeddingJobAttempt existingAttempt = createAttempt(embeddingJob, 1, CLAIM_TOKEN, 2L);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(existingAttempt));

        assertThatThrownBy(() -> service.start(JOB_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        then(embeddingJobAttemptRepository).should(never()).save(any());
        then(embeddingJobAttemptConverter).shouldHaveNoInteractions();
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
            Arguments.of(NOW),
            Arguments.of(NOW.minusNanos(1))
        );
    }

    private EmbeddingJob createOwnedJob(String claimToken, LocalDateTime lockExpiresAt) {
        WorkerNode workerNode = createWorker(WORKER_ID);
        EmbeddingJob embeddingJob = createJob(EmbeddingJobStatus.PENDING);
        embeddingJob.claim(workerNode, claimToken, NOW.minusMinutes(1), lockExpiresAt);
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
            .workerName("attempt-worker-" + workerId)
            .instanceId("attempt-worker-instance-" + workerId)
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(NOW)
            .startedAt(NOW.minusMinutes(2))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", workerId);
        return workerNode;
    }

    private EmbeddingJobAttempt createAttempt(
        EmbeddingJob embeddingJob,
        int attemptNo,
        String claimToken,
        Long workerId
    ) {
        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(createWorker(workerId))
            .attemptNo(attemptNo)
            .claimToken(claimToken)
            .startedAt(NOW.minusMinutes(1))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
        return attempt;
    }

    private StartedEmbeddingJobAttemptResponse createResponse(int attemptNo) {
        return new StartedEmbeddingJobAttemptResponse(
            ATTEMPT_ID,
            JOB_ID,
            attemptNo,
            WORKER_ID,
            AttemptStatus.STARTED,
            NOW
        );
    }

    private void assertNoAttemptWrite() {
        then(embeddingJobAttemptRepository).should(never())
            .findByEmbeddingJobIdAndClaimToken(any(), any());
        then(embeddingJobAttemptRepository).should(never()).save(any());
        then(embeddingJobAttemptConverter).shouldHaveNoInteractions();
    }
}
