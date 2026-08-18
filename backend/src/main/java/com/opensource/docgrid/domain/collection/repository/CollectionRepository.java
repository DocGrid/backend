package com.opensource.docgrid.domain.collection.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;

public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {

    /**
     * 사용자가 읽을 수 있는 컬렉션을 페이지 단위로 조회 (GET /collections).
     * 4가지 접근 경로: OWNER / PUBLIC / USER 직접 권한 / ROLE·DEPARTMENT live(부모 컬렉션 체인 상속 포함).
     * ACTIVE 상태만 대상으로 하며, keyword가 있으면 이름·설명 부분일치로도 필터링한다(keyword는 null 가능).
     *
     * <p>"읽을 수 있는 것 전체를 먼저 찾고 그중 일부를 다시 조회"하는 2단계 구조를 쓰지 않고,
     * COUNT(*) OVER() 윈도우 함수로 페이지 내용과 전체 개수를 한 쿼리에서 함께 계산한다 —
     * 콘텐츠 쿼리와 count 쿼리를 따로 두면 재귀 CTE가 두 번 계산되므로 일부러 합쳤다.
     */
    @Query(value = """
            WITH RECURSIVE collection_ancestors AS (
                SELECT id AS collection_id, id AS ancestor_id FROM collections
                UNION ALL
                SELECT ca.collection_id, c.parent_collection_id AS ancestor_id
                FROM collection_ancestors ca
                JOIN collections c ON c.id = ca.ancestor_id
                WHERE c.parent_collection_id IS NOT NULL
            ),
            readable AS (
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
            )
            SELECT
                c.id AS collection_id,
                c.name AS name,
                c.description AS description,
                c.owner_user_id AS owner_user_id,
                c.parent_collection_id AS parent_collection_id,
                c.visibility AS visibility,
                c.status AS status,
                c.created_at AS created_at,
                COUNT(*) OVER() AS total_count
            FROM collections c
            JOIN readable r ON r.id = c.id
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<CollectionRow> findReadableCollections(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /**
     * 직계 자식 중 사용자가 읽을 수 있는 것만 조회 (GET /collections/{id}/children).
     * 부모(및 그 위 조상들)로부터 상속받는 ROLE/DEPARTMENT 권한은 모든 자식이 공유하는 값이라
     * parent_ancestors 서브쿼리로 한 번만 계산한다 — 자식마다 다시 계산하지 않는다.
     */
    @Query(value = """
            WITH RECURSIVE parent_ancestors AS (
                SELECT id, parent_collection_id FROM collections WHERE id = :parentId
                UNION ALL
                SELECT c.id, c.parent_collection_id
                FROM collections c
                JOIN parent_ancestors a ON c.id = a.parent_collection_id
            )
            SELECT c.* FROM collections c
            WHERE c.parent_collection_id = :parentId AND c.status = 'ACTIVE'
              AND (
                c.owner_user_id = :userId
                OR c.visibility = 'PUBLIC'
                OR EXISTS (
                    SELECT 1 FROM collection_permissions cp
                    WHERE cp.collection_id = c.id AND cp.target_type = 'USER' AND cp.user_id = :userId
                      AND cp.can_read = true AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
                )
                OR EXISTS (
                    SELECT 1 FROM collection_permissions cp
                    JOIN user_roles ur ON ur.role_id = cp.role_id
                    WHERE cp.collection_id = c.id AND cp.target_type = 'ROLE' AND ur.user_id = :userId
                      AND cp.can_read = true AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
                )
                OR EXISTS (
                    SELECT 1 FROM collection_permissions cp
                    JOIN users u ON u.department_id = cp.department_id
                    WHERE cp.collection_id = c.id AND cp.target_type = 'DEPARTMENT' AND u.id = :userId
                      AND cp.can_read = true AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
                )
                OR EXISTS (
                    SELECT 1 FROM collection_permissions cp
                    JOIN parent_ancestors pa ON pa.id = cp.collection_id
                    JOIN user_roles ur ON ur.role_id = cp.role_id
                    WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId
                      AND cp.can_read = true AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
                )
                OR EXISTS (
                    SELECT 1 FROM collection_permissions cp
                    JOIN parent_ancestors pa ON pa.id = cp.collection_id
                    JOIN users u ON u.department_id = cp.department_id
                    WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId
                      AND cp.can_read = true AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
                )
              )
            ORDER BY c.created_at DESC, c.id DESC
            """, nativeQuery = true)
    List<DocumentCollection> findReadableChildren(@Param("parentId") Long parentId, @Param("userId") Long userId);

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
