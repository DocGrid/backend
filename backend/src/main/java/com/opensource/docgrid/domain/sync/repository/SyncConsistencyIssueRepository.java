package com.opensource.docgrid.domain.sync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;

/**
 * 일관성 Issue의 멱등 Key 조회와 관리자 목록 기반을 제공한다.
 */
public interface SyncConsistencyIssueRepository extends JpaRepository<SyncConsistencyIssue, Long> {

    Optional<SyncConsistencyIssue> findByIssueKey(String issueKey);

    List<SyncConsistencyIssue> findAllByDocumentVersionIdAndStatusIn(
        Long documentVersionId,
        List<SyncConsistencyIssueStatus> statuses
    );

    List<SyncConsistencyIssue> findAllByDocumentVersionIsNullAndStatusIn(
        List<SyncConsistencyIssueStatus> statuses
    );
}
