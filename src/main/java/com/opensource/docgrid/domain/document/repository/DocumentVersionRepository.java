package com.opensource.docgrid.domain.document.repository;

import java.util.Collection;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;

/**
 * Document Version의 일반 조회와 파이프라인 상태 전이를 위한 행 잠금 조회를 제공한다.
 *
 * <p>Chunk 생성 흐름은 Embedding Job을 먼저 잠근 뒤 이 Repository로 대상 Version을 잠가
 * 상태 변경과 동일 Version의 Chunk Set 생성을 직렬화한다.
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    boolean existsByDocumentIdAndStatusIn(Long documentId, Collection<DocumentVersionStatus> statuses);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

    @Query("SELECT COALESCE(MAX(dv.versionNo), 0) FROM DocumentVersion dv WHERE dv.document.id = :documentId")
    int findMaxVersionNo(@Param("documentId") Long documentId);

    /**
     * 지정한 Version을 현재 Transaction이 끝날 때까지 쓰기 잠금 상태로 조회한다.
     *
     * @param documentVersionId 잠글 Document Version 식별자
     * @return 잠금을 획득한 Version, 존재하지 않으면 빈 값
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT version FROM DocumentVersion version WHERE version.id = :documentVersionId")
    Optional<DocumentVersion> findByIdForUpdate(@Param("documentVersionId") Long documentVersionId);
}
