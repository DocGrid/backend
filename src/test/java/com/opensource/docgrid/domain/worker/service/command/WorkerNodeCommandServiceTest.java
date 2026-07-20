package com.opensource.docgrid.domain.worker.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerNodeCommandService 테스트")
class WorkerNodeCommandServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-20T06:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 20, 15, 0);

    @Mock
    private WorkerNodeRepository workerNodeRepository;

    private WorkerNodeCommandService workerNodeCommandService;

    @BeforeEach
    void setUp() {
        workerNodeCommandService = new WorkerNodeCommandService(workerNodeRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("Worker를 ACTIVE 상태로 등록하고 최초 Heartbeat를 기록한다")
    void register_savesActiveWorker() {
        given(workerNodeRepository.save(any(WorkerNode.class))).willAnswer(invocation -> {
            WorkerNode workerNode = invocation.getArgument(0);
            ReflectionTestUtils.setField(workerNode, "id", WorkerNodeFixture.WORKER_ID);
            return workerNode;
        });

        Long result = workerNodeCommandService.register(
            WorkerNodeFixture.WORKER_NAME,
            WorkerNodeFixture.INSTANCE_ID,
            WorkerNodeFixture.HOST_NAME,
            WorkerNodeFixture.IP_ADDRESS
        );

        ArgumentCaptor<WorkerNode> captor = ArgumentCaptor.forClass(WorkerNode.class);
        then(workerNodeRepository).should().save(captor.capture());
        WorkerNode savedWorker = captor.getValue();
        assertThat(result).isEqualTo(WorkerNodeFixture.WORKER_ID);
        assertThat(savedWorker.getWorkerName()).isEqualTo(WorkerNodeFixture.WORKER_NAME);
        assertThat(savedWorker.getInstanceId()).isEqualTo(WorkerNodeFixture.INSTANCE_ID);
        assertThat(savedWorker.getStatus()).isEqualTo(WorkerStatus.ACTIVE);
        assertThat(savedWorker.getStartedAt()).isEqualTo(NOW);
        assertThat(savedWorker.getLastHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Heartbeat는 ACTIVE와 IDLE Worker에 대해서만 현재 시각을 갱신한다")
    void heartbeat_updatesHeartbeatForLiveStatuses() {
        given(workerNodeRepository.updateHeartbeat(
            any(), any(), any(), any()
        )).willReturn(1);

        boolean result = workerNodeCommandService.heartbeat(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<WorkerStatus>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        then(workerNodeRepository).should().updateHeartbeat(
            eq(WorkerNodeFixture.WORKER_ID),
            eq(WorkerNodeFixture.INSTANCE_ID),
            eq(NOW),
            statusCaptor.capture()
        );
        assertThat(result).isTrue();
        assertThat(statusCaptor.getValue()).containsExactly(WorkerStatus.ACTIVE, WorkerStatus.IDLE);
    }

    @Test
    @DisplayName("Heartbeat 대상 Worker가 없으면 false를 반환한다")
    void heartbeat_returnsFalse_when_workerCannotBeUpdated() {
        given(workerNodeRepository.updateHeartbeat(any(), any(), any(), any())).willReturn(0);

        boolean result = workerNodeCommandService.heartbeat(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID
        );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("정상 종료 시 Worker를 STOPPED 상태로 갱신한다")
    void stop_marksWorkerStopped() {
        given(workerNodeRepository.markStopped(any(), any(), any(), any())).willReturn(1);

        boolean result = workerNodeCommandService.stop(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID
        );

        then(workerNodeRepository).should().markStopped(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID,
            NOW,
            WorkerStatus.STOPPED
        );
        assertThat(result).isTrue();
    }
}
