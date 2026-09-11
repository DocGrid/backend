package com.opensource.docgrid.domain.search.dto;

/**
 * 검색 접수 Transaction이 커밋한 대화방과 검색 원장 ID를 외부 실행 단계에 전달한다.
 *
 * <p>영속성 Context 경계를 넘는 엔티티 대신 식별자만 전달해 후속 성공·실패 처리가 각자의
 * Transaction에서 원장을 다시 조회하거나 조건부 갱신하게 한다.
 */
public record SearchAdmission(Long conversationId, Long queryId) {
}
