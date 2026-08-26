package com.opensource.docgrid.domain.search.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchQuery;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    /**
     * 대시보드 집계 카드의 최근 검색 요청 수. 기준 시각 이후 생성된 검색 Query를 센다.
     */
    long countByCreatedAtAfter(LocalDateTime since);

    // GET /search/{queryId} 재조회 시, 본인이 요청한 검색인지 소유권을 쿼리 조건으로 바로 걸러낸다.
    Optional<SearchQuery> findByIdAndUser_Id(Long id, Long userId);

    /** 대화 상세 화면은 과도한 응답을 막기 위해 최근 50개 질문만 읽는다. */
    List<SearchQuery> findTop50ByConversation_IdOrderByCreatedAtDesc(Long conversationId);
}
