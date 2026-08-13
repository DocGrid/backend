package com.opensource.docgrid.domain.sync.service.command;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.repository.SyncConsistencyIssueRepository;
import com.opensource.docgrid.domain.sync.service.SyncConsistencyObservation;

import lombok.RequiredArgsConstructor;

/**
 * 반복 탐지된 같은 불일치를 하나의 Issue로 수렴시키고 정상화된 Issue를 해결한다.
 *
 * <p>자동 복구 Event 생성은 Reconciler가 담당하며 이 Service는 Issue 생명주기만 변경한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncConsistencyIssueService {

    private static final List<SyncConsistencyIssueStatus> ACTIVE_STATUSES = List.of(
        SyncConsistencyIssueStatus.OPEN,
        SyncConsistencyIssueStatus.REPAIRING
    );

    private final SyncConsistencyIssueRepository syncConsistencyIssueRepository;

    public SyncConsistencyIssue detect(
        SyncConsistencyObservation observation,
        LocalDateTime detectedAt
    ) {
        return syncConsistencyIssueRepository.findByIssueKey(observation.issueKey())
            .map(issue -> detectAgain(issue, observation, detectedAt))
            .orElseGet(() -> syncConsistencyIssueRepository.save(
                SyncConsistencyIssue.builder()
                    .issueKey(observation.issueKey())
                    .issueType(observation.issueType())
                    .severity(observation.severity())
                    .document(observation.document())
                    .documentVersion(observation.documentVersion())
                    .embeddingModel(observation.embeddingModel())
                    .expectedJson(observation.expectedJson())
                    .actualJson(observation.actualJson())
                    .detectedAt(detectedAt)
                    .build()
            ));
    }

    public void resolveMissingObservations(
        Long documentVersionId,
        Set<String> detectedIssueKeys,
        LocalDateTime resolvedAt
    ) {
        syncConsistencyIssueRepository
            .findAllByDocumentVersionIdAndStatusIn(documentVersionId, ACTIVE_STATUSES)
            .stream()
            .filter(issue -> !detectedIssueKeys.contains(issue.getIssueKey()))
            .forEach(issue -> issue.resolve(resolvedAt, "Reconciliation 재검사에서 정상 상태를 확인했습니다."));
    }

    public void resolveMissingGlobalObservations(
        Set<String> detectedIssueKeys,
        LocalDateTime resolvedAt
    ) {
        syncConsistencyIssueRepository
            .findAllByDocumentVersionIsNullAndStatusIn(ACTIVE_STATUSES)
            .stream()
            .filter(issue -> !detectedIssueKeys.contains(issue.getIssueKey()))
            .forEach(issue -> issue.resolve(resolvedAt, "Reconciliation 전역 재검사에서 정상 상태를 확인했습니다."));
    }

    private SyncConsistencyIssue detectAgain(
        SyncConsistencyIssue issue,
        SyncConsistencyObservation observation,
        LocalDateTime detectedAt
    ) {
        issue.detectAgain(
            observation.severity(),
            observation.expectedJson(),
            observation.actualJson(),
            detectedAt
        );
        return issue;
    }
}
