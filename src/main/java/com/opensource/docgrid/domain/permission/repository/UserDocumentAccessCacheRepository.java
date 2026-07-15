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

    Optional<UserDocumentAccessCache> findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
            Long userId, Long documentId, AccessSourceType sourceType, Long sourceId);

    @Modifying
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.invalidatedAt = CURRENT_TIMESTAMP
            WHERE c.sourceType = :sourceType AND c.sourceId = :sourceId AND c.invalidatedAt IS NULL
            """)
    int bulkInvalidateBySource(@Param("sourceType") AccessSourceType sourceType,
                               @Param("sourceId") Long sourceId);

    @Modifying
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

    @Query("""
            SELECT c.document.id FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
            """)
    List<Long> findDocumentIdsByUserIdAndSourceTypeAndSourceId(
            @Param("userId") Long userId,
            @Param("sourceType") AccessSourceType sourceType,
            @Param("sourceId") Long sourceId);
}
