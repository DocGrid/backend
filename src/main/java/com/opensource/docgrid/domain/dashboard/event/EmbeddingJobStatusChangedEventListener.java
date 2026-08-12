package com.opensource.docgrid.domain.dashboard.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Embedding Job 상태 전이 이벤트를 받아 대시보드 갱신 플래그만 세우는 구독자.
 *
 * <p>이 클래스 자체는 대시보드를 갱신하지 않는다 — "커밋이 확실히 된 경우에만 갱신이 필요하다는
 * 신호를 남기는" 역할까지만 한다. 실제 집계 재계산과 WebSocket push는 이 플래그를 나중에 확인하는
 * {@code DashboardPushScheduler}가 한다.
 *
 * <p>{@code phase = AFTER_COMMIT}이라 이벤트를 발행한 Transaction이 실제로 커밋된 뒤에만 호출된다
 * — 롤백되면 이 메서드 자체가 실행되지 않으므로 확정되지 않은 상태 변화로 대시보드가 갱신되는 일이
 * 없다. 여기서 바로 집계를 계산하거나 push하지 않고 {@link DashboardUpdateFlag#markDirty()}만
 * 호출하는 이유는 {@code DashboardUpdateFlag}의 클래스 설명 참고 — burst 상황에서 이벤트 개수만큼
 * DB 조회가 늘어나는 걸 막기 위함이다.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingJobStatusChangedEventListener {

    private final DashboardUpdateFlag dashboardUpdateFlag;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingJobStatusChanged(EmbeddingJobStatusChangedEvent event) {
        dashboardUpdateFlag.markDirty();
    }
}
