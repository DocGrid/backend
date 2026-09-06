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

    /**
     * 관찰된 불일치를 Issue Key 기준으로 새로 저장하거나 기존 Issue의 탐지 정보를 갱신한다.
     *
     * @return 신규 또는 재탐지된 영속 Issue
     */
    public SyncConsistencyIssue detect(
        SyncConsistencyObservation observation,
        LocalDateTime detectedAt
    ) {
        // 1. 같은 논리적 불일치는 Issue 행을 추가하지 않고 기존 생명주기로 수렴시킨다.
        return syncConsistencyIssueRepository.findByIssueKey(observation.issueKey())
            .map(issue -> detectAgain(issue, observation, detectedAt))

            // 2. 최초 관찰이면 기대값·실제값과 복구 가능 여부를 함께 저장해 운영 판단 근거를 남긴다.
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
                    .repairable(observation.repairable())
                    .detectedAt(detectedAt)
                    .build()
            ));
    }

    /**
     * 특정 문서 Version 재검사에서 더 이상 관찰되지 않은 활성 Issue를 해결 상태로 전환한다.
     */
    public void resolveMissingObservations(
        Long documentVersionId,
        Set<String> detectedIssueKeys,
        LocalDateTime resolvedAt
    ) {
        // 1. 이번 검사 범위에 해당하는 아직 열린 Issue만 조회한다.
        syncConsistencyIssueRepository
            .findAllByDocumentVersionIdAndStatusIn(documentVersionId, ACTIVE_STATUSES)
            .stream()

            // 2. 재검사 결과에 없는 Issue만 정상화된 것으로 판단한다.
            .filter(issue -> !detectedIssueKeys.contains(issue.getIssueKey()))

            // 3. 정상화 근거와 해결 시각을 Entity 생명주기에 기록한다.
            .forEach(issue -> issue.resolve(resolvedAt, "Reconciliation 재검사에서 정상 상태를 확인했습니다."));
    }

    /**
     * 문서 Version에 속하지 않는 전역 재검사에서 사라진 활성 Issue를 해결 상태로 전환한다.
     */
    public void resolveMissingGlobalObservations(
        Set<String> detectedIssueKeys,
        LocalDateTime resolvedAt
    ) {
        // 1. 이번 검사 범위에 해당하는 전역 활성 Issue만 조회한다.
        syncConsistencyIssueRepository
            .findAllByDocumentVersionIsNullAndStatusIn(ACTIVE_STATUSES)
            .stream()

            // 2. 현재도 탐지된 Issue는 유지하고 사라진 Issue만 해결한다.
            .filter(issue -> !detectedIssueKeys.contains(issue.getIssueKey()))

            // 3. 전역 재검사를 통한 정상화임을 해결 사유에 남긴다.
            .forEach(issue -> issue.resolve(resolvedAt, "Reconciliation 전역 재검사에서 정상 상태를 확인했습니다."));
    }

    /**
     * 기존 Issue의 최신 증거와 재탐지 횟수를 갱신하고 같은 Entity를 반환한다.
     */
    private SyncConsistencyIssue detectAgain(
        SyncConsistencyIssue issue,
        SyncConsistencyObservation observation,
        LocalDateTime detectedAt
    ) {
        // 1. Issue Key로 고정되는 대상 정보는 유지하고 매 검사에서 달라질 수 있는 증거만 갱신한다.
        issue.detectAgain(
            observation.severity(),
            observation.expectedJson(),
            observation.actualJson(),
            observation.repairable(),
            detectedAt
        );

        // 2. 호출자가 신규 저장과 재탐지 결과를 동일한 반환 계약으로 사용할 수 있게 한다.
        return issue;
    }
}
