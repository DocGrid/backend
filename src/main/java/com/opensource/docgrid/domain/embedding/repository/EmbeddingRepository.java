package com.opensource.docgrid.domain.embedding.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.embedding.entity.Embedding;

/**
 * 문서 Chunk Embedding Set의 영속화와 Version·Model 단위 저장 개수 조회를 담당한다.
 *
 * <p>Embedding 생성 Transaction은 Job에 고정된 Model 범위의 저장 개수로 최초 실행, 재개,
 * 완료 재생과 부분 저장 모순을 구분하고 신규 Set은 한 Transaction에서 전체 저장한다.
 */
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    long countByDocumentVersionIdAndEmbeddingModelId(
        Long documentVersionId,
        Long embeddingModelId
    );
}
