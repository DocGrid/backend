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
import org.springframework.dao.OptimisticLockingFailureException;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

/**
 * RagJobWorker.processNext() 한 사이클의 동작만 검증한다 — 큐 조회(가장 오래된 PROCESSING 하나를
 * 꺼내는지), 처리 위임(RagFacade.processJob()으로 id를 넘기는지), 완료/실패 각각에서 요청자
 * 본인에게만 알림이 가는지가 검증 범위다. 실제 OllamaClient 호출이나 DB 반영 여부(dirty checking이
 * 실제로 먹히는지)는 이 테스트의 목(mock) 구조로는 증명할 수 없어 검증 범위 밖이다 —
 * RagJobWorkerIntegrationTest가 그 부분을 담당한다.
 */
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
        given(ragFacade.processJob(999L)).willReturn(true);

        ragJobWorker.processNext();

        // Worker는 detached entity를 그대로 넘기지 않고 id만 넘긴다 — processJob()이 자기 트랜잭션
        // 안에서 다시 조회해야 완료 처리(조건부 UPDATE)가 최신 상태 기준으로 실행된다.
        then(ragFacade).should(times(1)).processJob(999L);
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
    }

    @Test
    @DisplayName("경합(#288): processJob이 false를 반환하면(RagJobTimeoutSweeper가 이미 확정함) 알림을 보내지 않는다")
    void processNext_processJobLosesRace_doesNotNotify() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(job));
        given(ragFacade.processJob(999L)).willReturn(false);

        ragJobWorker.processNext();

        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("processJob이 예상 밖 예외를 던지면 job을 FAILED로 확정하고, Worker는 죽지 않고 이번 건만 건너뛴다")
    void processNext_unexpectedException_marksFailedAndSkipsJobWithoutCrashingWorker() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new RuntimeException("예상 밖 버그")).when(ragFacade).processJob(999L);
        given(ragFacade.markUnexpectedFailure(999L, "예상 밖 버그")).willReturn(true);

        ragJobWorker.processNext();

        // job을 PROCESSING으로 방치하면 Worker가 같은 job을 계속 다시 집어 무한 재시도하게 된다
        // (detached entity 버그와 같은 증상) — 그래서 반드시 FAILED로 확정해야 한다.
        then(ragFacade).should(times(1)).markUnexpectedFailure(999L, "예상 밖 버그");
        // FAILED로 확정된 이상 사용자도 결과(비록 실패 안내지만)를 받아야 하므로 알림은 그대로 간다.
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
    }

    @Test
    @DisplayName("다른 트랜잭션이 이미 같은 job을 처리했으면(낙관적 락 경합) FAILED로 덮어쓰지 않고 조용히 넘어간다")
    void processNext_optimisticLockingFailure_skipsWithoutOverwritingAsFailed() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(job));
        org.mockito.Mockito.doThrow(new OptimisticLockingFailureException("경합"))
            .when(ragFacade).processJob(999L);

        ragJobWorker.processNext();

        // 다른 트랜잭션이 이미 올바르게 처리한 결과이므로, 이걸 FAILED로 덮어쓰면 정상 처리된
        // 결과를 오답으로 바꿔버리는 2차 사고가 난다 — markUnexpectedFailure를 호출하면 안 된다.
        then(ragFacade).should(never()).markUnexpectedFailure(any(), any());
        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("한 job이 예외로 실패해도 다음 폴링에서 뒤에 대기 중인 job이 정상 처리된다")
    void processNext_afterUnexpectedFailure_nextPollingProcessesFollowingJob() {
        RagResponse failingJob = deepStubJob(1L, 100L, "user1@example.com");
        RagResponse nextJob = deepStubJob(2L, 200L, "user2@example.com");
        org.mockito.Mockito.doThrow(new RuntimeException("예상 밖 버그")).when(ragFacade).processJob(1L);

        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(failingJob));
        ragJobWorker.processNext();  // 1번째 폴링: failingJob 실패 → FAILED로 확정됨

        // FAILED로 확정됐으니 실제 DB에선 이제 findFirst...가 다음 대기 건(nextJob)을 돌려준다 —
        // 여기서는 그 상태 변화를 목으로 흉내낸다.
        given(ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING))
            .willReturn(Optional.of(nextJob));
        given(ragFacade.processJob(2L)).willReturn(true);
        ragJobWorker.processNext();  // 2번째 폴링: nextJob은 정상 처리돼야 한다

        then(ragFacade).should(times(1)).processJob(2L);
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user2@example.com", 200L);
    }

    private RagResponse deepStubJob(Long jobId, Long queryId, String userEmail) {
        RagResponse job = mock(RagResponse.class, RETURNS_DEEP_STUBS);
        given(job.getId()).willReturn(jobId);
        given(job.getQuery().getId()).willReturn(queryId);
        given(job.getQuery().getUser().getEmail()).willReturn(userEmail);
        return job;
    }
}
