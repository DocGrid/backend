package com.opensource.docgrid.domain.embedding.service.query;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.repository.LatestDocumentVersionProjection;
import com.opensource.docgrid.domain.embedding.converter.IndexingJobAdminConverter;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryPolicy;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 인덱싱 Job 목록·상세와 Attempt·Event 이력을 읽기 전용으로 조회한다.
 *
 * <p>필터와 고정 정렬을 Repository에 전달하고, Entity가 Transaction 밖으로 나가기 전에 민감 정보가
 * 제외된 DTO와 안정된 Pagination 응답으로 변환한다. 인덱싱 상태 변경은 담당하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IndexingJobAdminQueryService {

    private static final Sort JOB_SORT = Sort.by(
        Sort.Order.desc("createdAt"),
        Sort.Order.desc("id")
    );
    private static final Sort ATTEMPT_SORT = Sort.by(
        Sort.Order.desc("attemptNo"),
        Sort.Order.desc("id")
    );
    private static final Sort EVENT_SORT = Sort.by(
        Sort.Order.desc("occurredAt"),
        Sort.Order.desc("id")
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final IndexingJobAdminConverter indexingJobAdminConverter;
    private final EmbeddingJobManualRetryPolicy manualRetryPolicy;

    public PageResponse<AdminIndexingJobResponse> getJobs(
        EmbeddingJobStatus status,
        Long documentId,
        Long workerId,
        int page,
        int size
    ) {
        // 1. 외부 Sort 입력을 받지 않고 운영 Queue의 고정 정렬로 Page를 조회한다.
        Page<EmbeddingJob> jobs = embeddingJobRepository.findAdminJobs(
            status,
            documentId,
            workerId,
            PageRequest.of(page, size, JOB_SORT)
        );

        // 2. Transaction 안에서 모든 연관관계를 공개 DTO로 변환해 Entity 노출과 Lazy 조회를 막는다.
        Map<Long, EmbeddingJobManualRetryEligibility> retryEligibilities =
            resolveRetryEligibilities(jobs.getContent());
        List<AdminIndexingJobResponse> content = jobs.getContent().stream()
            .map(job -> indexingJobAdminConverter.toJobResponse(
                job,
                retryEligibilities.getOrDefault(
                    job.getId(),
                    EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED
                )
            ))
            .toList();
        return PageResponse.from(jobs, content);
    }

    public AdminIndexingJobResponse getJob(Long jobId) {
        EmbeddingJob job = embeddingJobRepository.findAdminDetailById(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
        EmbeddingJobManualRetryEligibility eligibility = resolveRetryEligibilities(List.of(job))
            .getOrDefault(job.getId(), EmbeddingJobManualRetryEligibility.JOB_NOT_FAILED);
        return indexingJobAdminConverter.toJobResponse(job, eligibility);
    }

    public PageResponse<AdminIndexingJobAttemptResponse> getAttempts(Long jobId, int page, int size) {
        // 1. 이력이 비어 있어도 Job 없음과 정상 빈 Page를 구분한다.
        validateJobExists(jobId);
        Page<EmbeddingJobAttempt> attempts = embeddingJobAttemptRepository.findAdminAttemptsByJobId(
            jobId,
            PageRequest.of(page, size, ATTEMPT_SORT)
        );

        // 2. 과거 Claim Token과 내부 오류 메시지를 읽지 않는 공개 DTO만 반환한다.
        List<AdminIndexingJobAttemptResponse> content = attempts.getContent().stream()
            .map(indexingJobAdminConverter::toAttemptResponse)
            .toList();
        return PageResponse.from(attempts, content);
    }

    public PageResponse<AdminIndexingEventResponse> getEvents(Long jobId, int page, int size) {
        // 1. Event가 없는 유효 Job과 존재하지 않는 Job을 명확히 구분한다.
        validateJobExists(jobId);
        Page<IndexingEvent> events = indexingEventRepository.findAllByEmbeddingJobId(
            jobId,
            PageRequest.of(page, size, EVENT_SORT)
        );

        // 2. 내부 Metadata JSON을 제외한 상태 전이 Snapshot만 공개 응답으로 변환한다.
        List<AdminIndexingEventResponse> content = events.getContent().stream()
            .map(indexingJobAdminConverter::toEventResponse)
            .toList();
        return PageResponse.from(events, content);
    }

    private void validateJobExists(Long jobId) {
        if (!embeddingJobRepository.existsById(jobId)) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND);
        }
    }

    private Map<Long, EmbeddingJobManualRetryEligibility> resolveRetryEligibilities(
        List<EmbeddingJob> jobs
    ) {
        List<EmbeddingJob> failedJobs = jobs.stream()
            .filter(job -> job.getStatus() == EmbeddingJobStatus.FAILED)
            .toList();
        if (failedJobs.isEmpty()) {
            return Map.of();
        }

        // 1. 화면 Page의 문서별 최신 Version과 활성 Job Version을 각각 한 번의 Query로 읽는다.
        Set<Long> documentIds = new HashSet<>();
        Set<Long> documentVersionIds = new HashSet<>();
        for (EmbeddingJob failedJob : failedJobs) {
            documentIds.add(failedJob.getDocumentVersion().getDocument().getId());
            documentVersionIds.add(failedJob.getDocumentVersion().getId());
        }
        Map<Long, Long> latestVersionIdsByDocumentId = new HashMap<>();
        for (LatestDocumentVersionProjection latestVersion
            : documentVersionRepository.findLatestVersionIdsByDocumentIds(documentIds)) {
            latestVersionIdsByDocumentId.put(
                latestVersion.getDocumentId(),
                latestVersion.getVersionId()
            );
        }
        Set<Long> liveJobVersionIds = new HashSet<>(
            embeddingJobRepository.findDocumentVersionIdsWithStatusIn(
                documentVersionIds,
                EmbeddingJobManualRetryPolicy.LIVE_JOB_STATUSES
            )
        );

        // 2. 조회와 Command가 공유하는 정책으로 Job별 버튼 상태를 계산한다.
        Map<Long, EmbeddingJobManualRetryEligibility> result = new HashMap<>();
        for (EmbeddingJob failedJob : failedJobs) {
            var version = failedJob.getDocumentVersion();
            var document = version.getDocument();
            result.put(failedJob.getId(), manualRetryPolicy.evaluate(
                failedJob,
                version,
                document,
                latestVersionIdsByDocumentId.get(document.getId()),
                liveJobVersionIds.contains(version.getId())
            ));
        }
        return result;
    }
}
