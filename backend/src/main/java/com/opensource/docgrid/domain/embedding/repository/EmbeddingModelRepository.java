package com.opensource.docgrid.domain.embedding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider;

/**
 * Embedding 모델 원장의 영속화와 활성 검색 모델 및 중복 모델 식별자 조회를 담당한다.
 */
public interface EmbeddingModelRepository extends JpaRepository<EmbeddingModel, Long> {

    /** 다중 기본 모델 설정을 숨기지 않고 Service에서 개수를 검증할 수 있도록 목록으로 반환한다. */
    List<EmbeddingModel> findAllByIsActiveTrueAndIsSearchableTrue();

    /** Provider·모델명·버전 조합이 이미 원장에 존재하는지 확인한다. */
    boolean existsByProviderAndModelNameAndModelVersion(
        EmbeddingProvider provider,
        String modelName,
        String modelVersion
    );
}
