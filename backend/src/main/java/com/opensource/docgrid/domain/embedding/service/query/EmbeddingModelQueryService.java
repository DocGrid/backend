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

/**
 * 현재 문서 인덱싱과 검색에 사용할 유일한 활성 Embedding 모델을 조회한다.
 *
 * <p>활성·검색 가능 모델이 정확히 하나라는 운영 불변식을 검사하며, 내부 Job 생성에는 Entity를,
 * 외부 조회 API에는 Token이나 연결 정보가 없는 응답 DTO를 제공한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingModelQueryService {

    private final EmbeddingModelRepository embeddingModelRepository;
    private final EmbeddingModelConverter embeddingModelConverter;

    /**
     * 후속 Embedding Job이 모델 선택을 고정할 수 있도록 유일한 활성 모델 Entity를 반환한다.
     */
    public EmbeddingModel getActiveModel() {
        // 1. 활성 상태이면서 검색에 사용할 수 있다고 표시된 모델을 모두 조회한다.
        List<EmbeddingModel> activeModels =
            embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue();

        // 2. 대상이 없으면 Job이 모델 없이 만들어지지 않도록 명시적인 구성 오류를 반환한다.
        if (activeModels.isEmpty()) {
            log.error("사용 가능한 임베딩 모델이 설정되지 않았습니다.");
            throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        }

        // 3. 둘 이상이면 비결정적으로 모델을 선택하지 않고 원장 구성 오류를 드러낸다.
        if (activeModels.size() > 1) {
            log.error("사용 가능한 임베딩 모델이 여러 개 설정되어 있습니다. count={}", activeModels.size());
            throw new DocGridException(ErrorCode.MULTIPLE_ACTIVE_EMBEDDING_MODELS);
        }

        // 4. 정확히 하나인 모델만 이후 Event와 Job의 공통 기준으로 사용한다.
        return activeModels.get(0);
    }

    /**
     * 현재 활성 모델을 외부 노출에 안전한 조회 응답으로 변환한다.
     */
    public EmbeddingModelResponse getActiveModelResponse() {
        return embeddingModelConverter.toResponse(getActiveModel());
    }
}
