package com.opensource.docgrid.domain.failover.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.failover.enums.FailoverEventType;
import com.opensource.docgrid.domain.failover.enums.FailoverStatus;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OpenSQL HA(Failover) 이벤트 로그 테이블.
 *
 * <p>역할: OpenSQL(DB) 계층의 장애 감지 및 복구 이벤트(Primary 장애, Standby 승격, 재연결 성공,
 * failover 실패 등)를 기록한다.
 * 이유: DB 장애 발생/복구 이력을 추적해 서비스 가용성 문제를 분석하고, 운영 대시보드에 노출하기 위함이다.
 * 관계: 다른 도메인 테이블을 직접 참조하지 않는 독립적인 로그 테이블이다.
 * index: event_type, status, occurred_at.
 *
 * <p>주의사항: 실제 DB HA 시스템과의 자동 연동이 어려운 MVP 단계에서는 시뮬레이션(수동/스크립트) 로그로
 * 시작할 수 있다. source_node/target_node는 호스트명 등 문자열로 저장한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "failover_events",
        indexes = {
                @Index(name = "idx_failover_events_event_type", columnList = "event_type"),
                @Index(name = "idx_failover_events_status", columnList = "status"),
                @Index(name = "idx_failover_events_occurred_at", columnList = "occurred_at")
        }
)
public class FailoverEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private FailoverEventType eventType;

    @Column(name = "source_node", length = 255)
    private String sourceNode;

    @Column(name = "target_node", length = 255)
    private String targetNode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FailoverStatus status;

    @Column(columnDefinition = "TEXT")
    private String message;

    // JSON 컬럼 임시 매핑(Hibernate JSON 타입 미설정) - 추후 OpenSQL JSON / Hibernate JSON 매핑으로 교체 필요
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Builder
    public FailoverEvent(FailoverEventType eventType, String sourceNode, String targetNode, FailoverStatus status,
                          String message, String metadataJson, LocalDateTime occurredAt) {
        this.eventType = eventType;
        this.sourceNode = sourceNode;
        this.targetNode = targetNode;
        this.status = status != null ? status : FailoverStatus.DETECTED;
        this.message = message;
        this.metadataJson = metadataJson;
        this.occurredAt = occurredAt;
    }

    public void markInProgress() {
        this.status = FailoverStatus.IN_PROGRESS;
    }

    public void markResolved(FailoverStatus status, LocalDateTime resolvedAt) {
        this.status = status;
        this.resolvedAt = resolvedAt;
    }
}
