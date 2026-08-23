package com.opensource.docgrid.domain.rag.service;

import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PROCESSING 상태인 RagResponse를 하나씩 순서대로 꺼내 처리하는 경량 Worker (#218).
 *
 * <p>{@code embedding_jobs}용 Worker(heartbeat·lease 복구 등 분산 처리 안전장치 포함, 26개 파일
 * 규모)와 달리, 이 Worker는 백엔드 인스턴스가 1개뿐이고 Ollama도 GPU 1개라 애초에 동시 처리가
 * 불가능하다는 전제 위에서 만들어졌다 — {@code @Scheduled} 폴링 하나로 충분하고, 여러 인스턴스
 * 간 조율(락·lease)은 필요 없다. Worker가 정확히 1개뿐이라는 사실 자체가 Ollama 호출의
 * 동시성 상한을 자연히 1로 만든다 — 기각했던 세마포어 게이트(#218 초안)가 하던 역할을 이
 * 구조가 대신한다.
 *
 * <p>{@code processJob()} 실행(=OllamaClient HTTP 호출, 최대 {@code ollama.generate-deadline})이
 * 끝나야 다음 폴링이 실행되므로, 폴링 주기 자체는 혼잡 여부와 무관하게 큐가 밀리지 않는 한
 * 크게 중요하지 않다 — PROCESSING 건이 있으면 그 즉시 다음 턴에 잡힌다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagJobWorker {

    private final RagResponseRepository ragResponseRepository;
    private final RagFacade ragFacade;
    private final RagWebSocketController ragWebSocketController;

    /**
     * 1초마다 실행되어 PROCESSING 중 가장 오래된 job 하나를 찾아 처리한다. 없으면 아무 일도
     * 하지 않고 다음 턴을 기다린다.
     *
     * <p>처리 결과는 세 갈래로 갈린다: ①정상 성공 — WebSocket 알림. ②{@link
     * OptimisticLockingFailureException} — 다른 트랜잭션이 이미 이 job을 처리했다는 뜻이라
     * 이미 올바르게 반영된 결과를 덮어쓰지 않도록 조용히 넘어간다(알림도 안 보낸다 — 그건
     * 먼저 처리한 쪽의 몫). ③그 외 예상 못한 예외 — FAILED로 강제 확정한 뒤 알림까지 보낸다
     * (실패했어도 화면이 영원히 로딩중으로 안 남도록).
     *
     * <p>①/③ 모두 알림은 {@code processJob}/{@code markUnexpectedFailure}가 반환하는
     * boolean을 확인한 뒤에만 보낸다 — RagJobTimeoutSweeper가 이 job을 이미 먼저 FAILED로
     * 확정해뒀다면(#288) 두 메서드 다 실제로는 아무것도 안 바꾸고 false를 반환하는데, 이 경우
     * 스위퍼가 이미 보낸 알림 외에 Worker가 중복으로 또 보낼 이유가 없다.
     */
    @Scheduled(fixedDelayString = "${rag.worker.polling-interval:1s}")
    public void processNext() {
        Optional<RagResponse> maybeJob = ragResponseRepository.findFirstByStatusOrderByCreatedAtAsc(ResultStatus.PROCESSING);
        if (maybeJob.isEmpty()) {
            return;
        }

        RagResponse job = maybeJob.get();
        /*
         * query/query.user는 findFirstByStatusOrderByCreatedAtAsc()의 @EntityGraph로 이미
         * 로딩돼 있어 detached 상태에서 읽어도 안전하다 — 완료 처리 자체는 조건부 UPDATE로
         * 이뤄지므로(#288) 이 job 인스턴스가 detached여도 상관없지만, processJob()이 이
         * 트랜잭션 시점 기준 최신 상태를 읽도록 id만 넘긴다.
         */
        Long queryId = job.getQuery().getId();
        String userEmail = job.getQuery().getUser().getEmail();

        try {
            if (ragFacade.processJob(job.getId())) {
                ragWebSocketController.notifyAnswerReady(userEmail, queryId);
            }
        } catch (OptimisticLockingFailureException e) {
            /*
             * 설계상 Worker는 인스턴스 1개를 전제하지만(클래스 주석 참고), 롤링 배포로 신·구
             * 인스턴스가 잠깐 겹치는 등 예외적으로 다른 트랜잭션이 같은 job을 먼저 처리했을 수
             * 있다. 이 경우 그 row는 이미 올바르게 SUCCESS/FAILED로 반영된 것이므로,
             * markUnexpectedFailure로 덮어쓰면 정상 처리된 결과를 오답으로 바꿔버리는 2차
             * 사고가 난다 — 조용히 다음 폴링으로 넘어간다.
             */
            log.warn("[RAG-WORKER] job이 이미 다른 트랜잭션에서 처리된 것으로 보임(경합) queryId={}", queryId);
        } catch (Exception e) {
            /*
             * processJob() 내부에서 Ollama 관련 실패는 이미 DocGridException으로 잡아 fallback
             * 처리하므로, 여기까지 올라오는 예외는 예상 밖의 버그다. Worker 스레드가 죽어서 큐
             * 전체가 멈추는 것보다는, 이 건을 건너뛰고 다음 폴링을 계속 도는 게 낫다. 단, job을
             * PROCESSING 상태로 방치하면 Worker가 같은 job을 계속 다시 집어 무한 재시도하게
             * 되므로(detached entity 버그와 같은 증상), 반드시 FAILED로 확정한 뒤 넘어간다.
             */
            log.error("[RAG-WORKER] job 처리 중 예상치 못한 예외 queryId={}", queryId, e);
            if (ragFacade.markUnexpectedFailure(job.getId(), e.getMessage())) {
                ragWebSocketController.notifyAnswerReady(userEmail, queryId);
            }
        }
    }
}
