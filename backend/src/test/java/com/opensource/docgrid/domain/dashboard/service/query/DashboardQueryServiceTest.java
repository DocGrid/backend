package com.opensource.docgrid.domain.dashboard.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
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

import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.service.query.WorkerNodeQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardQueryService 단위 테스트")
class DashboardQueryServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-10T06:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );

    @Mock private DocumentRepository documentRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private SearchQueryRepository searchQueryRepository;
    @Mock private WorkerNodeQueryService workerNodeQueryService;

    private DashboardQueryService dashboardQueryService;

    @BeforeEach
    void setUp() {
        dashboardQueryService = new DashboardQueryService(
            documentRepository,
            embeddingJobRepository,
            searchQueryRepository,
            workerNodeQueryService,
            FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("정상 케이스: 4개 카테고리 지표를 정확히 집계해서 조합한다")
    void getSummary_aggregatesAllCategories() {
        // Given
        given(documentRepository.countByDeletedAtIsNull()).willReturn(25368L);
        given(documentRepository.countByStatus(DocumentStatus.INDEXED)).willReturn(21742L);
        given(documentRepository.countByStatusIn(any())).willReturn(132L);

        given(embeddingJobRepository.countByStatus(EmbeddingJobStatus.PENDING)).willReturn(132L);
        given(embeddingJobRepository.countByStatus(EmbeddingJobStatus.PROCESSING)).willReturn(8L);
        given(embeddingJobRepository.countByStatus(EmbeddingJobStatus.FAILED)).willReturn(27L);
        given(embeddingJobRepository.findAverageProcessingMillis()).willReturn(3200.4);

        given(workerNodeQueryService.getWorkers()).willReturn(List.of(
            createWorker(WorkerStatus.ACTIVE),
            createWorker(WorkerStatus.IDLE),
            createWorker(WorkerStatus.DEAD)
        ));

        given(searchQueryRepository.countByCreatedAtAfter(
            LocalDateTime.of(2026, 8, 9, 15, 0, 0)
        )).willReturn(342L);

        // When
        DashboardSummaryResponse result = dashboardQueryService.getSummary();

        // Then
        assertThat(result.documents().total()).isEqualTo(25368L);
        assertThat(result.documents().searchable()).isEqualTo(21742L);
        assertThat(result.documents().pendingIndex()).isEqualTo(132L);

        assertThat(result.jobs().pending()).isEqualTo(132L);
        assertThat(result.jobs().processing()).isEqualTo(8L);
        assertThat(result.jobs().failed()).isEqualTo(27L);
        assertThat(result.jobs().avgProcessMs()).isEqualTo(3200L);

        assertThat(result.workers().activeCount()).isEqualTo(2L);
        assertThat(result.workers().totalCount()).isEqualTo(3L);

        assertThat(result.search().recent24hCount()).isEqualTo(342L);
    }

    @Test
    @DisplayName("완료된 Job이 없어 평균 처리 시간이 없으면 avgProcessMs는 null이다")
    void getSummary_returnsNullAvgProcessMs_whenNoCompletedJobs() {
        // Given
        given(documentRepository.countByDeletedAtIsNull()).willReturn(0L);
        given(documentRepository.countByStatus(any())).willReturn(0L);
        given(documentRepository.countByStatusIn(any())).willReturn(0L);
        given(embeddingJobRepository.countByStatus(any())).willReturn(0L);
        given(embeddingJobRepository.findAverageProcessingMillis()).willReturn(null);
        given(workerNodeQueryService.getWorkers()).willReturn(List.of());
        given(searchQueryRepository.countByCreatedAtAfter(any())).willReturn(0L);

        // When
        DashboardSummaryResponse result = dashboardQueryService.getSummary();

        // Then
        assertThat(result.jobs().avgProcessMs()).isNull();
    }

    @Test
    @DisplayName("STOPPED·DEAD Worker는 activeCount에서 제외한다")
    void getSummary_excludesStoppedAndDeadWorkersFromActiveCount() {
        // Given
        given(documentRepository.countByDeletedAtIsNull()).willReturn(0L);
        given(documentRepository.countByStatus(any())).willReturn(0L);
        given(documentRepository.countByStatusIn(any())).willReturn(0L);
        given(embeddingJobRepository.countByStatus(any())).willReturn(0L);
        given(embeddingJobRepository.findAverageProcessingMillis()).willReturn(null);
        given(searchQueryRepository.countByCreatedAtAfter(any())).willReturn(0L);

        given(workerNodeQueryService.getWorkers()).willReturn(List.of(
            createWorker(WorkerStatus.STOPPED),
            createWorker(WorkerStatus.DEAD)
        ));

        // When
        DashboardSummaryResponse result = dashboardQueryService.getSummary();

        // Then
        assertThat(result.workers().activeCount()).isZero();
        assertThat(result.workers().totalCount()).isEqualTo(2L);
    }

    private WorkerNodeResponse createWorker(WorkerStatus status) {
        return new WorkerNodeResponse(
            1L,
            "indexing-worker",
            "instance-1",
            "docgrid-api-01",
            "10.0.0.12",
            status,
            LocalDateTime.of(2026, 8, 10, 5, 59, 0),
            LocalDateTime.of(2026, 8, 10, 5, 0, 0),
            null
        );
    }
}
