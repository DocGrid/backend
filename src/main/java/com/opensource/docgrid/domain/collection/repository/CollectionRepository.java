package com.opensource.docgrid.domain.collection.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;

public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {

    // 소유자 기준 상태별 컬렉션 목록 조회 (GET /collections)
    List<DocumentCollection> findAllByOwnerIdAndStatus(Long ownerId, CollectionStatus status);
}
