package com.opensource.docgrid.domain.document.repository;

/**
 * 여러 문서의 최신 Version 식별자를 한 번에 읽기 위한 Repository Projection이다.
 *
 * <p>관리자 Job 목록의 수동 재처리 가능 여부를 계산할 때 문서별 추가 조회가 발생하지 않도록
 * Document ID와 최신 Version ID만 전달한다.
 */
public interface LatestDocumentVersionProjection {

    Long getDocumentId();

    Long getVersionId();
}
