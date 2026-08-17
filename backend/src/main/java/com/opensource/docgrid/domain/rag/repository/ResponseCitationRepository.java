package com.opensource.docgrid.domain.rag.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.rag.entity.ResponseCitation;

public interface ResponseCitationRepository extends JpaRepository<ResponseCitation, Long> {

    List<ResponseCitation> findByResponse_IdOrderByCitationOrder(Long responseId);
}
