package com.opensource.docgrid.domain.search.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchQuery;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    /**
     * 대시보드 집계 카드의 최근 검색 요청 수. 기준 시각 이후 생성된 검색 Query를 센다.
     */
    long countByCreatedAtAfter(LocalDateTime since);
}
