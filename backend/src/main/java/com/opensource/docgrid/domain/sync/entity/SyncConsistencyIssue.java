package com.opensource.docgrid.domain.sync.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Reconciler가 발견한 동일한 데이터 불일치를 하나의 관리 가능한 Issue로 보존한다.
 *
 * <p>issueKey는 반복 검사에서도 같은 행으로 수렴시키며, 기대·실제 Snapshot과 복구 Event를 함께 남긴다.
 * 이 Entity는 문제를 설명하고 복구 생명주기를 추적하지만 currentVersion 변경이나 물리 삭제를 직접
 * 수행하지 않는다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "sync_consistency_issues",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_sync_consistency_issues_issue_key",
        columnNames = "issue_key"
    ),
    indexes = {
        @Index(name = "idx_sync_consistency_issues_status_last_detected", columnList = "status, last_detected_at, id"),
        @Index(name = "idx_sync_consistency_issues_document_version", columnList = "document_version_id, status"),
        @Index(name = "idx_sync_consistency_issues_repair_event", columnList = "repair_event_id")
    }
)
public class SyncConsistencyIssue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_key", nullable = false, updatable = false, length = 255)
    private String issueKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, updatable = false, length = 50)
    private SyncConsistencyIssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncConsistencySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncConsistencyIssueStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_version_id")
    private DocumentVersion documentVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_model_id")
    private EmbeddingModel embeddingModel;

    @Column(name = "expected_json", columnDefinition = "TEXT")
    private String expectedJson;

    @Column(name = "actual_json", columnDefinition = "TEXT")
    private String actualJson;

    @Column(nullable = false)
    private boolean repairable;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    @Column(name = "repair_event_id")
    private UUID repairEventId;

    @Column(name = "repair_attempt_count", nullable = false)
    private int repairAttemptCount;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_message", columnDefinition = "TEXT")
    private String resolutionMessage;

    @Builder
    public SyncConsistencyIssue(
        String issueKey,
        SyncConsistencyIssueType issueType,
        SyncConsistencySeverity severity,
        Document document,
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        String expectedJson,
        String actualJson,
        boolean repairable,
        LocalDateTime detectedAt
    ) {
        this.issueKey = issueKey;
        this.issueType = issueType;
        this.severity = severity;
        this.status = SyncConsistencyIssueStatus.OPEN;
        this.document = document;
        this.documentVersion = documentVersion;
        this.embeddingModel = embeddingModel;
        this.expectedJson = expectedJson;
        this.actualJson = actualJson;
        this.repairable = repairable;
        this.detectedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
    }

    public void detectAgain(
        SyncConsistencySeverity newSeverity,
        String newExpectedJson,
        String newActualJson,
        boolean newRepairable,
        LocalDateTime detectedAgainAt
    ) {
        severity = newSeverity;
        expectedJson = newExpectedJson;
        actualJson = newActualJson;
        repairable = newRepairable;
        lastDetectedAt = detectedAgainAt;
        if (status == SyncConsistencyIssueStatus.RESOLVED) {
            status = SyncConsistencyIssueStatus.OPEN;
            resolvedAt = null;
            resolutionMessage = null;
        }
    }

    public void markRepairing(UUID newRepairEventId, LocalDateTime requestedAt) {
        if (status == SyncConsistencyIssueStatus.IGNORED || newRepairEventId == null || requestedAt == null) {
            throw new IllegalStateException("무시되지 않은 Issue에 유효한 Repair Event가 필요합니다.");
        }
        status = SyncConsistencyIssueStatus.REPAIRING;
        repairEventId = newRepairEventId;
        repairAttemptCount++;
        lastDetectedAt = requestedAt;
    }

    public void resolve(LocalDateTime resolvedAt, String message) {
        if (status == SyncConsistencyIssueStatus.IGNORED || resolvedAt == null) {
            throw new IllegalStateException("무시된 Issue는 자동 해결할 수 없습니다.");
        }
        status = SyncConsistencyIssueStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.resolutionMessage = message;
    }

    public void ignore(LocalDateTime ignoredAt, String message) {
        status = SyncConsistencyIssueStatus.IGNORED;
        resolvedAt = ignoredAt;
        resolutionMessage = message;
    }
}
