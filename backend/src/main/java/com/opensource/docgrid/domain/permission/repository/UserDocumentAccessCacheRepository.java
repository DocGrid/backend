package com.opensource.docgrid.domain.permission.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.UserDocumentAccessCache;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;

public interface UserDocumentAccessCacheRepository extends JpaRepository<UserDocumentAccessCache, Long> {

    /**
     * 그룹 1 — 단건 조회 (1개)
     */

    // 특정 권한 출처(sourceType + sourceId)의 캐시 단건 조회
    Optional<UserDocumentAccessCache> findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
            Long userId, Long documentId, AccessSourceType sourceType, Long sourceId);

    /**
     * 그룹 2 — {@code @Modifying} 벌크 쓰기 (3개)
     *
     * <p>캐시를 직접 INSERT/UPDATE하는 유일한 그룹. 벌크 UPDATE는 영속성 컨텍스트를 거치지
     * 않고 DB를 직접 치므로 clearAutomatically=true로 stale 엔티티를 방지한다.
     */

    // 특정 권한 출처에서 파생된 캐시 전체 무효화 (invalidated_at 일괄 설정)
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.invalidatedAt = CURRENT_TIMESTAMP
            WHERE c.sourceType = :sourceType AND c.sourceId = :sourceId AND c.invalidatedAt IS NULL
            """)
    int bulkInvalidateBySource(@Param("sourceType") AccessSourceType sourceType,
                               @Param("sourceId") Long sourceId);

    // 복수 출처에서 파생된 특정 문서 캐시 일괄 무효화 (컬렉션에서 문서 제거 시)
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.invalidatedAt = CURRENT_TIMESTAMP
            WHERE c.sourceType = :sourceType AND c.sourceId IN :sourceIds
              AND c.document.id = :documentId AND c.invalidatedAt IS NULL
            """)
    int bulkInvalidateBySourceIdsAndDocument(@Param("sourceType") AccessSourceType sourceType,
                                             @Param("sourceIds") List<Long> sourceIds,
                                             @Param("documentId") Long documentId);

    // 특정 권한 출처에서 파생된 캐시 전체 권한 갱신
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.canRead = :canRead, c.canWrite = :canWrite, c.canAdmin = :canAdmin,
                c.invalidatedAt = NULL, c.computedAt = CURRENT_TIMESTAMP, c.expiresAt = :expiresAt
            WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
            """)
    int bulkUpdateBySource(@Param("userId") Long userId,
                           @Param("sourceType") AccessSourceType sourceType,
                           @Param("sourceId") Long sourceId,
                           @Param("canRead") boolean canRead,
                           @Param("canWrite") boolean canWrite,
                           @Param("canAdmin") boolean canAdmin,
                           @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 그룹 3 — 벌크 INSERT 보조 조회 (1개)
     *
     * <p>bulkUpdateBySource로 기존 row를 갱신한 뒤, 여기서 "이미 캐시된 문서 ID"를 뽑아
     * 신규 문서만 걸러서 saveAll()로 INSERT한다(유니크 제약 위반 방지).
     */

    // 특정 권한 출처에서 이미 캐시된 문서 ID 목록 조회 (배치 INSERT 시 중복 방지용)
    @Query("""
            SELECT c.document.id FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
            """)
    List<Long> findDocumentIdsByUserIdAndSourceTypeAndSourceId(
            @Param("userId") Long userId,
            @Param("sourceType") AccessSourceType sourceType,
            @Param("sourceId") Long sourceId);

    /**
     * 그룹 4 — 유효성 존재 체크 (3개)
     *
     * <p>PermissionQueryService의 "3단계: USER 캐시" 판단에서 호출된다.
     */

    // 유효한 읽기 캐시 존재 여부 (invalidated_at IS NULL, 만료 미포함)
    @Query("""
            SELECT COUNT(c) > 0 FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.document.id = :documentId
              AND c.canRead = true
              AND c.invalidatedAt IS NULL
              AND (c.expiresAt IS NULL OR c.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsValidReadCache(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // 유효한 쓰기 캐시 존재 여부
    @Query("""
            SELECT COUNT(c) > 0 FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.document.id = :documentId
              AND c.canWrite = true
              AND c.invalidatedAt IS NULL
              AND (c.expiresAt IS NULL OR c.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsValidWriteCache(@Param("userId") Long userId, @Param("documentId") Long documentId);

    // 유효한 관리 캐시 존재 여부
    @Query("""
            SELECT COUNT(c) > 0 FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.document.id = :documentId
              AND c.canAdmin = true
              AND c.invalidatedAt IS NULL
              AND (c.expiresAt IS NULL OR c.expiresAt > CURRENT_TIMESTAMP)
            """)
    boolean existsValidAdminCache(@Param("userId") Long userId, @Param("documentId") Long documentId);
}
