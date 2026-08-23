package com.opensource.docgrid.domain.rag.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;

/**
 * RagJobTimeoutSweeper.sweep() 한 사이클의 동작만 검증한다 — stale job 조회, RagFacade에 실제
 * 강제 종료를 위임하는지, 그리고 그 결과(true/false)에 따라 알림을 보낼지 말지를 올바르게
 * 가르는지가 검증 범위다. forceFailIfProcessing()의 조건부 UPDATE 자체가 실제로 경합을 막는지는
 * 이 목(mock) 구조로 증명할 수 없어 검증 범위 밖이다 — RagResponseRepositoryTest가 그 부분을
 * 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagJobTimeoutSweeper 단위 테스트")
class RagJobTimeoutSweeperTest {

    @InjectMocks
    private RagJobTimeoutSweeper ragJobTimeoutSweeper;

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Mock
    private RagFacade ragFacade;

    @Mock
    private RagWebSocketController ragWebSocketController;

    @BeforeEach
    void setUp() {
        // @Value 필드는 Mockito @InjectMocks가 채워주지 않아, 실제 스프링 구동 시 주입되는
        // 값(기본 90s)을 테스트에서도 직접 넣어줘야 한다 — 안 그러면 sweep() 안 minus(null)에서 NPE.
        ReflectionTestUtils.setField(ragJobTimeoutSweeper, "staleThreshold", Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("stale job이 없으면 아무것도 하지 않는다")
    void sweep_noStaleJob_doesNothing() {
        given(ragResponseRepository.findByStatusAndCreatedAtBefore(any(), any())).willReturn(List.of());

        ragJobTimeoutSweeper.sweep();

        then(ragFacade).should(never()).failIfStillProcessing(any(), any());
        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("stale job을 실제로 강제 종료했으면(true) 요청자 본인에게 알림을 보낸다")
    void sweep_forcedFail_notifiesOwner() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findByStatusAndCreatedAtBefore(any(), any())).willReturn(List.of(job));
        given(ragFacade.failIfStillProcessing(999L, 100L)).willReturn(true);

        ragJobTimeoutSweeper.sweep();

        then(ragFacade).should(times(1)).failIfStillProcessing(999L, 100L);
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
    }

    @Test
    @DisplayName("이미 RagJobWorker가 먼저 끝낸 job이면(false) 알림을 보내지 않는다")
    void sweep_alreadyFinishedByWorker_doesNotNotify() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseRepository.findByStatusAndCreatedAtBefore(any(), any())).willReturn(List.of(job));
        given(ragFacade.failIfStillProcessing(999L, 100L)).willReturn(false);

        ragJobTimeoutSweeper.sweep();

        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("stale job이 여러 건이면 하나씩 전부 처리한다")
    void sweep_multipleStaleJobs_processesEachOne() {
        RagResponse first = deepStubJob(1L, 100L, "user1@example.com");
        RagResponse second = deepStubJob(2L, 200L, "user2@example.com");
        given(ragResponseRepository.findByStatusAndCreatedAtBefore(any(), any())).willReturn(List.of(first, second));
        given(ragFacade.failIfStillProcessing(1L, 100L)).willReturn(true);
        given(ragFacade.failIfStillProcessing(2L, 200L)).willReturn(true);

        ragJobTimeoutSweeper.sweep();

        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user1@example.com", 100L);
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
