package com.opensource.docgrid.domain.worker.lifecycle;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerHeartbeatScheduler 테스트")
class WorkerHeartbeatSchedulerTest {

    @InjectMocks
    private WorkerHeartbeatScheduler workerHeartbeatScheduler;

    @Mock private WorkerLifecycleManager workerLifecycleManager;
    @Mock private WorkerNodeCommandService workerNodeCommandService;

    @Test
    @DisplayName("등록된 Worker의 Heartbeat를 갱신한다")
    void updateHeartbeat_updatesRegisteredWorker() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.of(WorkerNodeFixture.WORKER_ID));
        given(workerLifecycleManager.getInstanceId()).willReturn(WorkerNodeFixture.INSTANCE_ID);
        given(workerNodeCommandService.heartbeat(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID
        )).willReturn(true);

        workerHeartbeatScheduler.updateHeartbeat();

        then(workerNodeCommandService).should().heartbeat(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.INSTANCE_ID
        );
    }

    @Test
    @DisplayName("Worker 등록 전에는 Heartbeat를 시도하지 않는다")
    void updateHeartbeat_skips_when_workerIsNotRegistered() {
        given(workerLifecycleManager.getWorkerId()).willReturn(Optional.empty());

        workerHeartbeatScheduler.updateHeartbeat();

        then(workerNodeCommandService).should(never()).heartbeat(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString()
        );
    }
}
