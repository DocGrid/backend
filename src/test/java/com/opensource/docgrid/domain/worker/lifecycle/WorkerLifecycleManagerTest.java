package com.opensource.docgrid.domain.worker.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerLifecycleManager 테스트")
class WorkerLifecycleManagerTest {

    @Mock
    private WorkerNodeCommandService workerNodeCommandService;

    private WorkerLifecycleManager workerLifecycleManager;

    @BeforeEach
    void setUp() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setName(WorkerNodeFixture.WORKER_NAME);
        workerLifecycleManager = new WorkerLifecycleManager(workerNodeCommandService, properties);
    }

    @Test
    @DisplayName("애플리케이션 준비 시 UUID instanceId로 Worker를 한 번 등록한다")
    void registerWorker_registersOnce() {
        given(workerNodeCommandService.register(
            eq(WorkerNodeFixture.WORKER_NAME),
            any(String.class),
            any(String.class),
            any()
        )).willReturn(WorkerNodeFixture.WORKER_ID);

        workerLifecycleManager.registerWorker();
        workerLifecycleManager.registerWorker();

        assertThat(workerLifecycleManager.getWorkerId()).contains(WorkerNodeFixture.WORKER_ID);
        assertThat(workerLifecycleManager.getInstanceId()).hasSize(36);
        then(workerNodeCommandService).should(times(1)).register(
            eq(WorkerNodeFixture.WORKER_NAME),
            eq(workerLifecycleManager.getInstanceId()),
            any(String.class),
            any()
        );
    }

    @Test
    @DisplayName("애플리케이션 종료 시 현재 Worker를 STOPPED 처리한다")
    void stopWorker_stopsRegisteredWorker() {
        given(workerNodeCommandService.register(any(), any(), any(), any()))
            .willReturn(WorkerNodeFixture.WORKER_ID);
        given(workerNodeCommandService.stop(any(), any())).willReturn(true);
        workerLifecycleManager.registerWorker();

        workerLifecycleManager.stopWorker();

        then(workerNodeCommandService).should().stop(
            WorkerNodeFixture.WORKER_ID,
            workerLifecycleManager.getInstanceId()
        );
        assertThat(workerLifecycleManager.getWorkerId()).isEmpty();
    }
}
