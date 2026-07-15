package com.opensource.docgrid.domain.permission.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.permission.entity.UserDocumentAccessCache;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;

public interface UserDocumentAccessCacheRepository extends JpaRepository<UserDocumentAccessCache, Long> {

    Optional<UserDocumentAccessCache> findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
            Long userId, Long documentId, AccessSourceType sourceType, Long sourceId);
}
