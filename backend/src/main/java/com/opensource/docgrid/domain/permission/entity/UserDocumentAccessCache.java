package com.opensource.docgrid.domain.permission.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.user.entity.User;
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
 * 사용자-문서 접근 권한 캐시 테이블.
 *
 * <p>역할: 검색 시 대량의 문서를 빠르게 pre-filter하기 위한 가속용 캐시.
 * 이유: 매 검색 요청마다 권한 테이블 전체를 live join하면 느리므로, OWNER/직접 USER 권한만 미리 계산해 둔다.
 * 관계: user_id -> User, document_id -> Document. source_type/source_id로 이 캐시 row가 어떤 권한에서
 * 파생되었는지(OWNER, DIRECT_DOCUMENT_PERMISSION, DIRECT_COLLECTION_PERMISSION) 추적한다.
 * unique 제약: (user_id, document_id, source_type, source_id) 조합은 유일해야 한다.
 * index: (user_id, document_id), (user_id, can_read), document_id, invalidated_at, expires_at.
 *
 * <p>매우 중요: 이 테이블은 권한의 source of truth가 아니다. 직접 부여된 USER 권한(및 소유권)만 저장하는
 * 검색 pre-filter 가속 캐시일 뿐이며, PUBLIC/ROLE/DEPARTMENT 기반 권한은 여기에 저장하지 않는다.
 * 따라서 이 캐시만으로 최종 접근 허용 여부를 판단해서는 안 되며, 최종 응답을 내려주기 전에는
 * 반드시 live permission check(라이브 권한 검증)를 거쳐야 한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "user_document_access_cache",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_document_access_cache_user_id_document_id_source_type_source_id",
                        columnNames = {"user_id", "document_id", "source_type", "source_id"}
                )
        },
        indexes = {
                @Index(name = "idx_user_document_access_cache_user_id_document_id", columnList = "user_id, document_id"),
                @Index(name = "idx_user_document_access_cache_user_id_can_read", columnList = "user_id, can_read"),
                @Index(name = "idx_user_document_access_cache_document_id", columnList = "document_id"),
                @Index(name = "idx_user_document_access_cache_invalidated_at", columnList = "invalidated_at"),
                @Index(name = "idx_user_document_access_cache_expires_at", columnList = "expires_at")
        }
)
public class UserDocumentAccessCache extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 접근 권한을 캐시하는 대상 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 접근 대상 문서
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "can_read", nullable = false)
    private boolean canRead;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite;

    @Column(name = "can_admin", nullable = false)
    private boolean canAdmin;

    // 이 캐시 row가 어떤 권한에서 파생되었는지(OWNER/직접 문서 권한/직접 컬렉션 권한)
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private AccessSourceType sourceType;

    // 파생 근거가 된 권한 레코드의 id(document_permissions.id 또는 collection_permissions.id), OWNER면 null
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    // 권한 변경 등으로 캐시가 무효화된 시각, null이면 유효
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    // 캐시 만료 시각, null이면 만료 없음
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * 한 사용자·문서·권한 출처 조합의 계산된 접근 비트와 유효 기간을 생성한다.
     */
    @Builder
    public UserDocumentAccessCache(User user, Document document, boolean canRead, boolean canWrite,
                                    boolean canAdmin, AccessSourceType sourceType, Long sourceId,
                                    LocalDateTime computedAt, LocalDateTime invalidatedAt, LocalDateTime expiresAt) {
        this.user = user;
        this.document = document;
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.canAdmin = canAdmin;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.computedAt = computedAt;
        this.invalidatedAt = invalidatedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 현재 권한 원장의 접근 비트와 만료 시각으로 기존 캐시를 다시 활성화한다.
     */
    public void grant(boolean canRead, boolean canWrite, boolean canAdmin, LocalDateTime expiresAt) {
        // 1. 읽기·쓰기·관리 권한을 원장의 현재 Snapshot으로 교체한다.
        this.canRead = canRead;
        this.canWrite = canWrite;
        this.canAdmin = canAdmin;

        // 2. 무효화 표시를 제거하고 재계산 시각과 새 만료 시각을 기록한다.
        this.invalidatedAt = null;
        this.computedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    /**
     * 권한 원장 변경으로 더 이상 검색 pre-filter에 사용할 수 없는 캐시에 무효화 시각을 기록한다.
     */
    public void invalidate() {
        this.invalidatedAt = LocalDateTime.now();
    }
}
