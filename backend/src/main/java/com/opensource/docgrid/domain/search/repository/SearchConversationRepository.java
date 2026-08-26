package com.opensource.docgrid.domain.search.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchConversation;

/**
 * 사용자 소유권 조건과 최근 활동순 목록 조회를 제공하는 대화방 Repository.
 *
 * <p>다른 사용자의 대화 존재 여부를 노출하지 않도록 단건 조회도 항상 userId를 함께 조건으로 사용한다.
 */
public interface SearchConversationRepository extends JpaRepository<SearchConversation, Long> {

    Optional<SearchConversation> findByIdAndUser_Id(Long id, Long userId);

    Page<SearchConversation> findByUser_IdOrderByLastMessageAtDesc(Long userId, Pageable pageable);
}
