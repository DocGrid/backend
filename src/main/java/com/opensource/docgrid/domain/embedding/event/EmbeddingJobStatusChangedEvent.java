package com.opensource.docgrid.domain.embedding.event;

/**
 * Embedding Job의 상태가 바뀌었음을 알리는 마커 이벤트.
 *
 * <p>대시보드가 최신 집계를 다시 계산해야 한다는 신호일 뿐이라 어떤 상태에서 어떤 상태로
 * 바뀌었는지는 담지 않는다. 구독 측은 이벤트 발생 시점에 항상 전체 집계를 새로 계산하므로
 * 세부 상태를 실어봐야 쓰이지 않는다.
 */
public record EmbeddingJobStatusChangedEvent(Long jobId) {
}
