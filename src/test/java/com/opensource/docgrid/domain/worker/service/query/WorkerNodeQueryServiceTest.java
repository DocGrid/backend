package com.opensource.docgrid.domain.worker.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.converter.WorkerNodeConverter;
import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerNodeQueryService 테스트")
class WorkerNodeQueryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-20T06:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );

    @Mock private WorkerNodeRepository workerNodeRepository;
    @Mock private WorkerNodeConverter workerNodeConverter;

    private WorkerNodeQueryService workerNodeQueryService;

    @BeforeEach
    void setUp() {
        IndexingWorkerProperties properties = new IndexingWorkerProperties();
        properties.setDeadThreshold(Duration.ofSeconds(30));
        workerNodeQueryService = new WorkerNodeQueryService(
            workerNodeRepository,
            workerNodeConverter,
            properties,
            FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("Heartbeat 만료 Worker를 DEAD 상태로 변환한다")
    void getWorkers_convertsExpiredHeartbeatToDead() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(
            LocalDateTime.of(2026, 7, 20, 14, 59, 29)
        );
        WorkerNodeResponse expected = createResponse(WorkerStatus.DEAD);
        given(workerNodeRepository.findAllByOrderByStartedAtDescIdDesc()).willReturn(List.of(workerNode));
        given(workerNodeConverter.toResponse(workerNode, WorkerStatus.DEAD)).willReturn(expected);

        List<WorkerNodeResponse> result = workerNodeQueryService.getWorkers();

        assertThat(result).containsExactly(expected);
        then(workerNodeConverter).should().toResponse(workerNode, WorkerStatus.DEAD);
    }

    @Test
    @DisplayName("최근 Heartbeat Worker를 ACTIVE 상태로 변환한다")
    void getWorkers_keepsRecentHeartbeatActive() {
        WorkerNode workerNode = WorkerNodeFixture.createActiveWorker(
            LocalDateTime.of(2026, 7, 20, 14, 59, 31)
        );
        WorkerNodeResponse expected = createResponse(WorkerStatus.ACTIVE);
        given(workerNodeRepository.findAllByOrderByStartedAtDescIdDesc()).willReturn(List.of(workerNode));
        given(workerNodeConverter.toResponse(workerNode, WorkerStatus.ACTIVE)).willReturn(expected);

        List<WorkerNodeResponse> result = workerNodeQueryService.getWorkers();

        assertThat(result).containsExactly(expected);
        then(workerNodeConverter).should().toResponse(workerNode, WorkerStatus.ACTIVE);
    }

    private WorkerNodeResponse createResponse(WorkerStatus status) {
        return new WorkerNodeResponse(
            WorkerNodeFixture.WORKER_ID,
            WorkerNodeFixture.WORKER_NAME,
            WorkerNodeFixture.INSTANCE_ID,
            WorkerNodeFixture.HOST_NAME,
            WorkerNodeFixture.IP_ADDRESS,
            status,
            LocalDateTime.of(2026, 7, 20, 14, 59, 31),
            WorkerNodeFixture.STARTED_AT,
            null
        );
    }
}
