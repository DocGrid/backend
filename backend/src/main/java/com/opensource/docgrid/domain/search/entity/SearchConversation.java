package com.opensource.docgrid.domain.search.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 사용자의 검색 질문과 RAG 답변을 묶는 대화방 루트 엔티티.
 *
 * <p>대화 제목과 최근 메시지 시각만 관리하며, 실제 질문·답변 본문은 기존
 * {@link SearchQuery}와 RagResponse가 계속 단일 진실 소스로 보관한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "search_conversations",
    indexes = {
        @Index(name = "idx_search_conversations_user_last_message", columnList = "user_id,last_message_at")
    }
)
public class SearchConversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @Builder
    public SearchConversation(User user, String title, LocalDateTime lastMessageAt) {
        this.user = user;
        this.title = title;
        this.lastMessageAt = lastMessageAt;
    }

    /** 새 질문이 추가된 시각을 기록해 대화 목록을 최근 활동순으로 유지한다. */
    public void recordMessage(LocalDateTime messageAt) {
        this.lastMessageAt = messageAt;
    }
}
