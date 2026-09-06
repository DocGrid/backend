package com.opensource.docgrid.domain.worker.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인덱싱 상태 변경 이벤트 로그 테이블.
 *
 * <p>역할: embedding_jobs의 상태 변경(생성/lock/파싱/청킹/임베딩/색인 완료/실패/재시도 등) 이력을 시간순으로
 * 기록하는 append-only 로그 테이블이다.
 * 이유: Dashboard 등에서 문서 하나의 인덱싱 타임라인을 보여주거나, 장애 발생 시점을 분석하기 위해 필요하다.
 * 관계: embedding_job_id -> EmbeddingJob.
 * index: embedding_job_id, event_type, occurred_at.
 *
 * <p>주의사항: 이 테이블은 append-only 로그이므로 기존 행을 수정하지 않고 새 이벤트만 추가한다.
 * from_status/to_status는 조회 편의를 위한 문자열 스냅샷이다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "indexing_events",
        indexes = {
                @Index(name = "idx_indexing_events_embedding_job_id", columnList = "embedding_job_id"),
                @Index(name = "idx_indexing_events_event_type", columnList = "event_type"),
                @Index(name = "idx_indexing_events_occurred_at", columnList = "occurred_at")
        }
)
public class IndexingEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 이벤트가 발생한 임베딩 작업
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "embedding_job_id", nullable = false)
    private EmbeddingJob embeddingJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private IndexingEventType eventType;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", length = 30)
    private String toStatus;

    @Column(columnDefinition = "TEXT")
    private String message;

    // 현재 Schema가 TEXT이므로 문자열로 보존한다. JSONB 전환 시 Flyway와 Hibernate 매핑을 함께 바꿔야 한다.
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Builder
    public IndexingEvent(EmbeddingJob embeddingJob, IndexingEventType eventType, String fromStatus, String toStatus,
                          String message, String metadataJson, LocalDateTime occurredAt) {
        this.embeddingJob = embeddingJob;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.message = message;
        this.metadataJson = metadataJson;
        this.occurredAt = occurredAt;
    }
}
