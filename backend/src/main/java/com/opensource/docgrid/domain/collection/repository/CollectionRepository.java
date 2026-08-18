package com.opensource.docgrid.domain.collection.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;

public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {

    /**
     * 사용자가 읽을 수 있는 컬렉션 ID 전체 (GET /collections pre-filter).
     * 4가지 접근 경로: OWNER / PUBLIC / USER 직접 권한 / ROLE·DEPARTMENT live(부모 컬렉션 체인 상속 포함).
     * ACTIVE 상태만 대상으로 하며, keyword가 있으면 이름·설명 부분일치로도 필터링한다(keyword는 null 가능).
     */
    @Query(value = """
            WITH RECURSIVE collection_ancestors AS (
                SELECT id AS collection_id, id AS ancestor_id FROM collections
                UNION ALL
                SELECT ca.collection_id, c.parent_collection_id AS ancestor_id
                FROM collection_ancestors ca
                JOIN collections c ON c.id = ca.ancestor_id
                WHERE c.parent_collection_id IS NOT NULL
            )
            SELECT c.id FROM collections c
            WHERE c.owner_user_id = :userId AND c.status = 'ACTIVE'
              AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
            UNION
            SELECT c.id FROM collections c
            WHERE c.visibility = 'PUBLIC' AND c.status = 'ACTIVE'
              AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
            UNION
            SELECT c.id FROM collections c
              JOIN collection_permissions cp ON cp.collection_id = c.id
            WHERE cp.target_type = 'USER' AND cp.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
              AND c.status = 'ACTIVE'
              AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
            UNION
            SELECT c.id FROM collections c
              JOIN collection_ancestors ca ON ca.collection_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN user_roles ur ON ur.role_id = cp.role_id
            WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
              AND c.status = 'ACTIVE'
              AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
            UNION
            SELECT c.id FROM collections c
              JOIN collection_ancestors ca ON ca.collection_id = c.id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN users u ON u.department_id = cp.department_id
            WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
              AND c.status = 'ACTIVE'
              AND (:keyword IS NULL OR c.name ILIKE CONCAT('%', :keyword, '%') OR c.description ILIKE CONCAT('%', :keyword, '%'))
            """, nativeQuery = true)
    List<Long> findReadableCollectionIds(@Param("userId") Long userId, @Param("keyword") String keyword);

    // pre-filter로 걸러진 ID를 받아 정렬·페이징만 담당 (GET /collections)
    @Query("SELECT c FROM DocumentCollection c JOIN FETCH c.owner WHERE c.id IN :ids")
    Page<DocumentCollection> findAllByIdIn(@Param("ids") List<Long> ids, Pageable pageable);

    // 직계 자식 컬렉션 목록 조회 (GET /collections/{id}/children)
    List<DocumentCollection> findAllByParentCollectionIdAndStatus(Long parentCollectionId, CollectionStatus status);

    /**
     * 자기 자신 + 모든 조상 컬렉션 ID (권한 상속 판단용).
     * 삭제된 조상도 결과에 포함한다 — 삭제된 컬렉션은 권한이 비어있어 무해하고,
     * status 필터를 넣으면 중간 조상이 삭제됐을 때 그 위 조상으로 체인이 끊기는 문제가 생긴다.
     */
    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT id, parent_collection_id FROM collections WHERE id = :collectionId
                UNION ALL
                SELECT c.id, c.parent_collection_id
                FROM collections c
                JOIN ancestors a ON c.id = a.parent_collection_id
            )
            SELECT id FROM ancestors
            """, nativeQuery = true)
    List<Long> findAncestorIdsInclusive(@Param("collectionId") Long collectionId);

    /**
     * 자기 자신 + 모든 후손 컬렉션 ID (cascade 삭제 대상 판단용).
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT id, parent_collection_id FROM collections WHERE id = :collectionId
                UNION ALL
                SELECT c.id, c.parent_collection_id
                FROM collections c
                JOIN descendants d ON c.parent_collection_id = d.id
            )
            SELECT id FROM descendants
            """, nativeQuery = true)
    List<Long> findDescendantIdsInclusive(@Param("collectionId") Long collectionId);

    /**
     * 문서가 속한 모든 컬렉션(N:M) + 그 컬렉션들 각각의 조상 전체 ID (문서 권한 상속 판단용).
     */
    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT c.id, c.parent_collection_id
                FROM collections c
                WHERE c.id IN (
                    SELECT DISTINCT cd.collection_id FROM collection_documents cd WHERE cd.document_id = :documentId
                )
                UNION ALL
                SELECT c.id, c.parent_collection_id
                FROM collections c
                JOIN ancestors a ON c.id = a.parent_collection_id
            )
            SELECT DISTINCT id FROM ancestors
            """, nativeQuery = true)
    List<Long> findEffectiveCollectionIdsForDocument(@Param("documentId") Long documentId);
}
