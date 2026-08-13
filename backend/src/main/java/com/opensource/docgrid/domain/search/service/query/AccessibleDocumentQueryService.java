package com.opensource.docgrid.domain.search.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.repository.DocumentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 검색 pre-filter 서비스.
 *
 * <p>벡터 검색 실행 전에 사용자가 읽을 수 있는 문서 ID 목록을 반환한다.
 * 결과가 빈 목록이면 호출 측에서 벡터 검색을 건너뛰어야 한다.
 *
 * <p>접근 가능 조건 (OR):
 * <ul>
 *   <li>OWNER — 문서 소유자</li>
 *   <li>PUBLIC — visibility = PUBLIC</li>
 *   <li>USER 캐시 — user_document_access_cache에 유효한 읽기 캐시 존재</li>
 *   <li>ROLE live — 사용자 역할 기반 document_permissions 또는 collection_permissions</li>
 *   <li>DEPT live — 사용자 부서 기반 document_permissions 또는 collection_permissions</li>
 * </ul>
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class AccessibleDocumentQueryService {

    private final DocumentRepository documentRepository;

    /**
     * 사용자가 읽을 수 있는 INDEXED 문서 ID 목록을 반환한다.
     *
     * @param userId       요청 사용자 ID
     * @param collectionId 컬렉션 범위 검색 시 컬렉션 ID, 전체 검색이면 null
     * @return 접근 가능한 문서 ID 목록 (빈 목록이면 검색 불필요)
     */
    public List<Long> findReadableDocumentIds(Long userId, Long collectionId) {
        if (collectionId != null) {
            return documentRepository.findReadableDocumentIdsInCollection(userId, collectionId);
        }
        return documentRepository.findReadableDocumentIds(userId);
    }
}
