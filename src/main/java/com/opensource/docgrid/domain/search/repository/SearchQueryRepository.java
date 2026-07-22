package com.opensource.docgrid.domain.search.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchQuery;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {
}
