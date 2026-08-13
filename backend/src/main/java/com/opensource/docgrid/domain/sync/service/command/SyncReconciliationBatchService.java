package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.entity.SyncConsistencyIssue;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.service.SyncConsistencyInspector;
import com.opensource.docgrid.domain.sync.service.SyncConsistencyObservation;
import com.opensource.docgrid.domain.sync.service.SyncOrphanInspector;

import lombok.RequiredArgsConstructor;

/**
 * 작은 ID Cursor Batch에서 원장과 파생 데이터를 비교하고 Issue 및 안전한 Repair Event를 원자적으로 기록한다.
 *
 * <p>누락 Job·Vector처럼 재실행 가능한 불일치만 Outbox로 복구하며, 데이터 삭제나 currentVersion 변경은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncReconciliationBatchService {

    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final SyncConsistencyInspector syncConsistencyInspector;
    private final SyncConsistencyIssueService syncConsistencyIssueService;
    private final SyncOrphanInspector syncOrphanInspector;
    private final SyncEventWriter syncEventWriter;
    private final SyncReconciliationProperties properties;
    private final Clock clock;

    public SyncReconciliationBatchResult reconcile(
        UUID runId,
        long startCursor,
        SyncReconciliationMode mode
    ) {
        LocalDateTime inspectedAt = LocalDateTime.now(clock);
        EmbeddingModel activeModel = embeddingModelQueryService.getActiveModel();
        List<DocumentVersion> versions = documentVersionRepository.findReconciliationBatchAfterId(
            startCursor,
            PageRequest.of(0, properties.getBatchSize())
        );
        int detectedCount = 0;
        int repairRequestedCount = 0;

        // 1. 첫 Cursor에서는 Version FK 밖의 전역 고아 데이터도 한 번 검사한다.
        if (startCursor == 0L) {
            Set<String> orphanIssueKeys = new HashSet<>();
            for (SyncConsistencyObservation observation : syncOrphanInspector.inspect()) {
                orphanIssueKeys.add(observation.issueKey());
                syncConsistencyIssueService.detect(observation, inspectedAt);
                detectedCount++;
            }
            syncConsistencyIssueService.resolveMissingGlobalObservations(orphanIssueKeys, inspectedAt);
        }

        // 2. 각 Version을 독립 Issue Key 집합으로 검사해 반복 실행을 같은 Issue 행에 수렴시킨다.
        for (DocumentVersion version : versions) {
            List<SyncConsistencyObservation> observations = syncConsistencyInspector.inspect(
                version,
                activeModel,
                inspectedAt
            );
            Set<String> detectedIssueKeys = new HashSet<>();
            for (SyncConsistencyObservation observation : observations) {
                detectedIssueKeys.add(observation.issueKey());
                SyncConsistencyIssue issue = syncConsistencyIssueService.detect(observation, inspectedAt);
                detectedCount++;

                // 3. REPAIR 모드에서도 명시적으로 안전하다고 판정된 OPEN Issue만 Outbox에 기록한다.
                if (mode == SyncReconciliationMode.REPAIR
                    && observation.repairable()
                    && issue.getStatus() == SyncConsistencyIssueStatus.OPEN) {
                    SyncOutboxEvent repairEvent = syncEventWriter.recordDocumentReindexRequested(
                        version,
                        activeModel,
                        repairRequestKey(issue)
                    );
                    issue.markRepairing(repairEvent.getEventId(), inspectedAt);
                    repairRequestedCount++;
                }
            }

            // 4. 이전 실행의 활성 Issue가 이번 검사에서 사라졌다면 정상화된 것으로 종결한다.
            syncConsistencyIssueService.resolveMissingObservations(
                version.getId(),
                detectedIssueKeys,
                inspectedAt
            );
        }

        // 5. 마지막 ID를 다음 Cursor로 반환해 다음 Batch가 Offset 재탐색 없이 이어지게 한다.
        long endCursor = versions.isEmpty()
            ? startCursor
            : versions.get(versions.size() - 1).getId();
        return new SyncReconciliationBatchResult(
            runId,
            startCursor,
            endCursor,
            versions.size(),
            detectedCount,
            repairRequestedCount,
            versions.size() == properties.getBatchSize()
        );
    }

    private String repairRequestKey(SyncConsistencyIssue issue) {
        return "reconcile:%s:attempt:%d".formatted(
            issue.getIssueKey(),
            issue.getRepairAttemptCount() + 1
        );
    }
}
