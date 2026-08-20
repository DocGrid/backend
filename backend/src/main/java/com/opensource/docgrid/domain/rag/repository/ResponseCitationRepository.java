package com.opensource.docgrid.domain.rag.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.rag.entity.ResponseCitation;

/**
 * ResponseCitation 엔티티에 대한 JPA Repository.
 *
 * <p>ResponseCitation은 RAG(Retrieval-Augmented Generation)에서 생성된 응답과 관련된 인용 정보를 저장한다.
 */

public interface ResponseCitationRepository extends JpaRepository<ResponseCitation, Long> {

    /**
     * 특정 답변(responseId)이 인용한 근거 목록을, 저장 시 매긴 순서(citation_order) 그대로 반환한다.
     * PromptBuilder가 프롬프트에 매긴 라벨 순서와 항상 일치하도록 같은 정렬 기준을 재사용한다.
     */
    List<ResponseCitation> findByResponse_IdOrderByCitationOrder(Long responseId);
}
