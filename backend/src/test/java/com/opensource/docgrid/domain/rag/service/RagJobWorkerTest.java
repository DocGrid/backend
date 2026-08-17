package com.opensource.docgrid.domain.rag.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagJobWorker 단위 테스트")
class RagJobWorkerTest {

    @InjectMocks
    private RagJobWorker ragJobWorker;

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Mock
    private RagFacade ragFacade;

    @Mock
    private RagWebSocketController ragWebSocketController;

    @Test
    @DisplayName("PROCESSING 건이 없으면 아무것도 하지 않는다")
    void processNext_noPendingJob_doesNothing() {
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.empty());

        ragJobWorker.processNext();

        then(ragFacade).should(never()).processJob(any());
        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("PROCESSING 건이 있으면 처리하고, 요청자 본인에게만 완료를 push한다")
    void processNext_pendingJobExists_processesAndNotifiesOwner() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(job));

        ragJobWorker.processNext();

        // Worker는 detached entity를 그대로 넘기지 않고 id만 넘긴다 — processJob()이 자기 트랜잭션
        // 안에서 다시 조회해야 markSuccess 등의 변경이 dirty checking으로 실제 반영된다.
        then(ragFacade).should(times(1)).processJob(999L);
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
    }

    @Test
    @DisplayName("processJob이 예상 밖 예외를 던져도 Worker는 죽지 않고 이번 건만 건너뛴다(push 생략)")
    void processNext_unexpectedException_skipsJobWithoutCrashingWorker() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("예상 밖 버그")).when(ragFacade).processJob(999L);

        ragJobWorker.processNext();

        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    private RagResponse deepStubJob(Long jobId, Long queryId, String userEmail) {
        RagResponse job = mock(RagResponse.class, RETURNS_DEEP_STUBS);
        given(job.getId()).willReturn(jobId);
        given(job.getQuery().getId()).willReturn(queryId);
        given(job.getQuery().getUser().getEmail()).willReturn(userEmail);
        return job;
    }
}
