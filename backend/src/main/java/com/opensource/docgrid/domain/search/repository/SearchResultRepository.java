package com.opensource.docgrid.domain.search.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.search.entity.SearchResult;

public interface SearchResultRepository extends JpaRepository<SearchResult, Long> {

    // RAG Worker가 citation을 재구성할 때, PromptBuilder에 넘겼던 것과 동일한 순서로 다시 읽는다.
    List<SearchResult> findByQuery_IdOrderByRankNo(Long queryId);
}
