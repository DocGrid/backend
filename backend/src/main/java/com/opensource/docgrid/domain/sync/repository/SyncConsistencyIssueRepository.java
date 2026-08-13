package com.opensource.docgrid.domain.sync.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

/**
 * 일관성 Issue의 멱등 Key 조회와 관리자 목록 기반을 제공한다.
 */
public interface SyncConsistencyIssueRepository extends JpaRepository<SyncConsistencyIssue, Long> {

    long countByStatus(SyncConsistencyIssueStatus status);

    long countByStatusAndRepairEventIdIsNotNullAndResolvedAtGreaterThanEqual(
        SyncConsistencyIssueStatus status,
        LocalDateTime since
    );

    Optional<SyncConsistencyIssue> findByIssueKey(String issueKey);

    List<SyncConsistencyIssue> findAllByDocumentVersionIdAndStatusIn(
        Long documentVersionId,
        List<SyncConsistencyIssueStatus> statuses
    );

    List<SyncConsistencyIssue> findAllByDocumentVersionIsNullAndStatusIn(
        List<SyncConsistencyIssueStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT issue FROM SyncConsistencyIssue issue WHERE issue.id = :issueId")
    Optional<SyncConsistencyIssue> findByIdForUpdate(@Param("issueId") Long issueId);

    @Query(
        value = """
            SELECT issue
              FROM SyncConsistencyIssue issue
              LEFT JOIN FETCH issue.document
              LEFT JOIN FETCH issue.documentVersion
              LEFT JOIN FETCH issue.embeddingModel
             WHERE (:status IS NULL OR issue.status = :status)
               AND (:issueType IS NULL OR issue.issueType = :issueType)
               AND (:severity IS NULL OR issue.severity = :severity)
            """,
        countQuery = """
            SELECT COUNT(issue)
              FROM SyncConsistencyIssue issue
             WHERE (:status IS NULL OR issue.status = :status)
               AND (:issueType IS NULL OR issue.issueType = :issueType)
               AND (:severity IS NULL OR issue.severity = :severity)
            """
    )
    Page<SyncConsistencyIssue> findAdminIssues(
        @Param("status") SyncConsistencyIssueStatus status,
        @Param("issueType") SyncConsistencyIssueType issueType,
        @Param("severity") SyncConsistencySeverity severity,
        Pageable pageable
    );

    @Query(value = """
        SELECT COUNT(*)
          FROM sync_consistency_issues issue
          JOIN sync_outbox_events event ON event.event_id = issue.repair_event_id
         WHERE issue.status = 'REPAIRING'
           AND event.status = 'FAILED'
        """, nativeQuery = true)
    long countFailedRepairIssues();
}
