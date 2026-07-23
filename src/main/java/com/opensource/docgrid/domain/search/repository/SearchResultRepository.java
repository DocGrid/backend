package com.opensource.docgrid.domain.search.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchResult;

public interface SearchResultRepository extends JpaRepository<SearchResult, Long> {
}
