package com.opensource.docgrid.domain.dashboard.service.query;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.worker.dto.response.WorkerNodeResponse;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.service.query.WorkerNodeQueryService;

import lombok.RequiredArgsConstructor;

/**
 * RAGOps Dashboard 집계 지표를 조회한다.
 *
 * <p>자체 테이블은 소유하지 않으며 A 담당자가 소유한 문서·작업·검색 Repository를 읽기 전용으로 집계하고,
 * Worker 현황은 Heartbeat 기준 실시간 상태 계산 로직을 새로 만들지 않고 {@link WorkerNodeQueryService}를
 * 그대로 재사용한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class DashboardQueryService {

    private static final List<DocumentStatus> PENDING_INDEX_STATUSES =
        List.of(DocumentStatus.UPLOADED, DocumentStatus.INDEXING);

    private final DocumentRepository documentRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final WorkerNodeQueryService workerNodeQueryService;
    private final Clock clock;

    // 대시보드 요약 지표 조회
    public DashboardSummaryResponse getSummary() {
        return new DashboardSummaryResponse(
            getDocumentsSummary(),
            getJobsSummary(),
            getWorkersSummary(),
            getSearchSummary()
        );
    }

    // 문서 현황 집계
    private DocumentsSummaryResponse getDocumentsSummary() {
        return new DocumentsSummaryResponse(
            documentRepository.countByDeletedAtIsNull(),
            documentRepository.countByStatus(DocumentStatus.INDEXED),
            documentRepository.countByStatusIn(PENDING_INDEX_STATUSES)
        );
    }

    // 인덱싱 작업 현황 집계
    private JobsSummaryResponse getJobsSummary() {
        Double averageMillis = embeddingJobRepository.findAverageProcessingMillis();
        return new JobsSummaryResponse(
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.PENDING),
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.PROCESSING),
            embeddingJobRepository.countByStatus(EmbeddingJobStatus.FAILED),
            averageMillis == null ? null : Math.round(averageMillis)
        );
    }

    // Worker 현황 집계
    private WorkersSummaryResponse getWorkersSummary() {
        List<WorkerNodeResponse> workers = workerNodeQueryService.getWorkers();
        long activeCount = workers.stream()
            .filter(worker -> worker.status() == WorkerStatus.ACTIVE || worker.status() == WorkerStatus.IDLE)
            .count();
        return new WorkersSummaryResponse(activeCount, workers.size());
    }

    // 최근 24시간 검색 쿼리 수 집계
    private SearchSummaryResponse getSearchSummary() {
        LocalDateTime since = LocalDateTime.now(clock).minusHours(24);
        return new SearchSummaryResponse(searchQueryRepository.countByCreatedAtAfter(since));
    }
}
