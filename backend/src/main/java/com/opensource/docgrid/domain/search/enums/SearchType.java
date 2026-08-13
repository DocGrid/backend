package com.opensource.docgrid.domain.search.enums;

/**
 * 검색 방식.
 * VECTOR: 벡터 유사도 검색, KEYWORD: 키워드 검색, HYBRID: 두 방식 혼합.
 */
public enum SearchType {
    VECTOR,
    KEYWORD,
    HYBRID
}
