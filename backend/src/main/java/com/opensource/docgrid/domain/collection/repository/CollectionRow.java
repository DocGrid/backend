package com.opensource.docgrid.domain.collection.repository;

import java.time.LocalDateTime;

/**
 * 읽기 가능한 컬렉션 목록 네이티브 쿼리 프로젝션 (GET /collections).
 *
 * <p>컬럼 alias가 snake_case일 때 Spring Data JPA가 camelCase getter로 자동 매핑한다.
 * totalCount는 COUNT(*) OVER()로 모든 행에 동일하게 실려오는 전체 개수다.
 */
public interface CollectionRow {
    Long getCollectionId();
    String getName();
    String getDescription();
    Long getOwnerUserId();
    String getOwnerName();
    Long getParentCollectionId();
    String getVisibility();
    String getStatus();
    LocalDateTime getCreatedAt();
    Long getTotalCount();
}
