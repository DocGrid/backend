package com.opensource.docgrid.domain.sync.service.handler;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.service.SyncEventHandler;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 모델 활성화 Event가 현재 검색 모델 원장과 일치하는지 검증한다.
 *
 * <p>대량 문서 재인덱싱 대상 산출과 안전한 Repair Event 생성은 Reconciler 단계가 담당한다. 이 Handler는
 * 비활성 모델 Event가 성공 처리되어 잘못된 기준점이 남는 것을 차단한다.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingModelActivatedSyncEventHandler implements SyncEventHandler {

    private final EmbeddingModelRepository embeddingModelRepository;

    /** 이 Handler가 Embedding 모델 활성화 Event만 처리함을 선언한다. */
    @Override
    public Set<SyncEventType> supportedTypes() {
        return Set.of(SyncEventType.EMBEDDING_MODEL_ACTIVATED);
    }

    /**
     * Event의 모델이 현재도 활성·검색 가능한 원장 상태인지 확인한다.
     */
    @Override
    public void handle(SyncOutboxEvent event) {
        // 1. Event Aggregate ID로 최신 EmbeddingModel 원장을 다시 조회한다.
        EmbeddingModel model = embeddingModelRepository.findById(event.getAggregateId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT));

        // 2. 비활성화되었거나 검색 불가능한 모델의 과거 Event를 성공 처리하지 않는다.
        if (!model.isActive() || !model.isSearchable()) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_INCONSISTENT);
        }
    }
}
