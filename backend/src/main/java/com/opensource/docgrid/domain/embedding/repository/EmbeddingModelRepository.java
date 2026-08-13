package com.opensource.docgrid.domain.embedding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;

public interface EmbeddingModelRepository extends JpaRepository<EmbeddingModel, Long> {

    // 다중 기본 모델 설정을 숨기지 않고 서비스에서 개수를 검증할 수 있도록 List로 반환한다.
    List<EmbeddingModel> findAllByIsActiveTrueAndIsSearchableTrue();

    boolean existsByProviderAndModelNameAndModelVersion(
        EmbeddingProvider provider,
        String modelName,
        String modelVersion
    );
}
