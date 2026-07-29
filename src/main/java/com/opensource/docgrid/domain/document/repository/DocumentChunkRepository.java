package com.opensource.docgrid.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;

/**
 * 문서 Version별 Chunk 존재 여부와 개수 조회 및 Chunk Set 전체 저장을 담당한다.
 *
 * <p>Chunk 생성 Transaction은 기존 결과 확인에 존재·개수 조회를 사용하고, 신규 결과는
 * {@link JpaRepository#saveAllAndFlush(Iterable)}로 같은 Transaction 안에서 즉시 검증한다.
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    boolean existsByDocumentVersionId(Long documentVersionId);

    long countByDocumentVersionId(Long documentVersionId);
}
