package com.opensource.docgrid.domain.embedding.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.embedding.converter.IndexingJobAdminConverter;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("IndexingJobAdminQueryService 테스트")
class IndexingJobAdminQueryServiceTest {

    private static final Long JOB_ID = 10L;

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private IndexingJobAdminConverter indexingJobAdminConverter;

    @InjectMocks private IndexingJobAdminQueryService indexingJobAdminQueryService;

    @Test
    @DisplayName("Job 목록 필터와 고정 최신순 Pagination을 Repository에 전달한다")
    void getJobs_passesFiltersAndFixedSort() {
        EmbeddingJob job = org.mockito.Mockito.mock(EmbeddingJob.class);
        AdminIndexingJobResponse response = org.mockito.Mockito.mock(AdminIndexingJobResponse.class);
        given(embeddingJobRepository.findAdminJobs(
            org.mockito.ArgumentMatchers.eq(EmbeddingJobStatus.FAILED),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(job), PageRequest.of(1, 5), 6));
        given(indexingJobAdminConverter.toJobResponse(job)).willReturn(response);

        PageResponse<AdminIndexingJobResponse> result = indexingJobAdminQueryService.getJobs(
            EmbeddingJobStatus.FAILED,
            3L,
            7L,
            1,
            5
        );

        assertThat(result.content()).containsExactly(response);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(6);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(embeddingJobRepository).should().findAdminJobs(
            org.mockito.ArgumentMatchers.eq(EmbeddingJobStatus.FAILED),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq(7L),
            pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("Job 상세를 공개 응답으로 변환한다")
    void getJob_convertsAdminDetail() {
        EmbeddingJob job = org.mockito.Mockito.mock(EmbeddingJob.class);
        AdminIndexingJobResponse expected = org.mockito.Mockito.mock(AdminIndexingJobResponse.class);
        given(embeddingJobRepository.findAdminDetailById(JOB_ID)).willReturn(Optional.of(job));
        given(indexingJobAdminConverter.toJobResponse(job)).willReturn(expected);

        assertThat(indexingJobAdminQueryService.getJob(JOB_ID)).isSameAs(expected);
    }

    @Test
    @DisplayName("존재하지 않는 Job 상세는 404 오류로 변환한다")
    void getJob_throws_whenJobDoesNotExist() {
        given(embeddingJobRepository.findAdminDetailById(JOB_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> indexingJobAdminQueryService.getJob(JOB_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_FOUND);
    }

    @Test
    @DisplayName("Attempt를 최근 번호 순으로 페이지 변환한다")
    void getAttempts_returnsConvertedPage() {
        EmbeddingJobAttempt attempt = org.mockito.Mockito.mock(EmbeddingJobAttempt.class);
        AdminIndexingJobAttemptResponse response = org.mockito.Mockito.mock(
            AdminIndexingJobAttemptResponse.class
        );
        given(embeddingJobRepository.existsById(JOB_ID)).willReturn(true);
        given(embeddingJobAttemptRepository.findAdminAttemptsByJobId(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(attempt), PageRequest.of(0, 20), 1));
        given(indexingJobAdminConverter.toAttemptResponse(attempt)).willReturn(response);

        PageResponse<AdminIndexingJobAttemptResponse> result =
            indexingJobAdminQueryService.getAttempts(JOB_ID, 0, 20);

        assertThat(result.content()).containsExactly(response);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(embeddingJobAttemptRepository).should().findAdminAttemptsByJobId(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("attemptNo").isDescending()).isTrue();
    }

    @Test
    @DisplayName("Event를 최근 발생 순으로 페이지 변환한다")
    void getEvents_returnsConvertedPage() {
        IndexingEvent event = org.mockito.Mockito.mock(IndexingEvent.class);
        AdminIndexingEventResponse response = org.mockito.Mockito.mock(AdminIndexingEventResponse.class);
        given(embeddingJobRepository.existsById(JOB_ID)).willReturn(true);
        given(indexingEventRepository.findAllByEmbeddingJobId(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));
        given(indexingJobAdminConverter.toEventResponse(event)).willReturn(response);

        PageResponse<AdminIndexingEventResponse> result =
            indexingJobAdminQueryService.getEvents(JOB_ID, 0, 20);

        assertThat(result.content()).containsExactly(response);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(indexingEventRepository).should().findAllByEmbeddingJobId(
            org.mockito.ArgumentMatchers.eq(JOB_ID),
            pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("occurredAt").isDescending()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 Job의 이력은 Repository 조회 전에 거부한다")
    void getAttempts_throwsBeforeHistoryQuery_whenJobDoesNotExist() {
        given(embeddingJobRepository.existsById(JOB_ID)).willReturn(false);

        assertThatThrownBy(() -> indexingJobAdminQueryService.getAttempts(JOB_ID, 0, 20))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDING_JOB_NOT_FOUND);
        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
    }
}
