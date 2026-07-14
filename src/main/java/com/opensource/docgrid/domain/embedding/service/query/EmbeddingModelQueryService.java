package com.opensource.docgrid.domain.embedding.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingModelConverter;
import com.opensource.docgrid.domain.embedding.dto.response.EmbeddingModelResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingModelQueryService {

    private final EmbeddingModelRepository embeddingModelRepository;
    private final EmbeddingModelConverter embeddingModelConverter;

    // 후속 embedding_jobs 생성 시 모델 Entity를 연관관계에 고정하기 위한 내부 조회 메서드다.
    public EmbeddingModel getActiveModel() {
        List<EmbeddingModel> activeModels =
            embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue();

        if (activeModels.isEmpty()) {
            log.error("사용 가능한 임베딩 모델이 설정되지 않았습니다.");
            throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        }

        if (activeModels.size() > 1) {
            log.error("사용 가능한 임베딩 모델이 여러 개 설정되어 있습니다. count={}", activeModels.size());
            throw new DocGridException(ErrorCode.MULTIPLE_ACTIVE_EMBEDDING_MODELS);
        }

        return activeModels.get(0);
    }

    public EmbeddingModelResponse getActiveModelResponse() {
        return embeddingModelConverter.toResponse(getActiveModel());
    }
}
