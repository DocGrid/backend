package com.opensource.docgrid.domain.collection.service.query;

import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class CollectionQueryService {

    private static final List<String> LISTABLE_DOCUMENT_STATUSES = EnumSet.complementOf(
            EnumSet.of(DocumentStatus.DELETED)
    ).stream().map(DocumentStatus::name).toList();
    private static final Sort COLLECTION_DOCUMENT_SORT = Sort.by(
            Sort.Order.desc("addedAt"),
            Sort.Order.desc("id")
    );
    private static final Sort COLLECTION_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    private final CollectionRepository collectionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final DocumentRepository documentRepository;
    private final CollectionConverter collectionConverter;
    private final PermissionQueryService permissionQueryService;

    // 컬렉션 단건 조회 — 소유자, PUBLIC, 또는 권한을 부여받은 사용자만 가능
    public CollectionResponse getCollection(Long userId, Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!permissionQueryService.canReadCollection(userId, collection)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }
        return collectionConverter.toResponse(collection);
    }

    /**
     * 사용자가 읽을 수 있는 컬렉션 목록 페이지 조회 (owner + PUBLIC + 권한부여 + 부모 상속, ACTIVE만).
     * keyword가 있으면 이름·설명 부분일치로도 필터링한다.
     */
    public PageResponse<CollectionResponse> getCollections(Long userId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, COLLECTION_SORT);
        List<Long> readableIds = collectionRepository.findReadableCollectionIds(userId, keyword);
        if (readableIds.isEmpty()) {
            return PageResponse.from(Page.empty(pageable), List.of());
        }

        Page<DocumentCollection> collections = collectionRepository.findAllByIdIn(readableIds, pageable);
        List<CollectionResponse> content = collections.getContent().stream()
                .map(collectionConverter::toResponse)
                .toList();
        return PageResponse.from(collections, content);
    }

    // 직계 자식 컬렉션 목록 조회 — 부모 읽기 권한 확인 후, 자식 각각의 읽기 권한도 확인
    // (자식 owner/visibility가 부모와 다를 수 있으므로 부모 권한만으로 자식을 노출하면 안 됨)
    public List<CollectionResponse> getChildren(Long userId, Long collectionId) {
        DocumentCollection parent = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!permissionQueryService.canReadCollection(userId, parent)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        return collectionRepository.findAllByParentCollectionIdAndStatus(collectionId, CollectionStatus.ACTIVE)
                .stream()
                .filter(child -> permissionQueryService.canReadCollection(userId, child))
                .map(collectionConverter::toResponse)
                .toList();
    }

    /**
     * 컬렉션을 볼 수 있고 각 문서도 읽을 수 있는 항목만 페이지 응답으로 반환한다.
     */
    public PageResponse<CollectionDocumentListItemResponse> getCollectionDocuments(
            Long userId,
            Long collectionId,
            int page,
            int size) {
        // 1. 컬렉션 자체의 존재·삭제·읽기 권한을 문서 Metadata 조회보다 먼저 검증한다.
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!permissionQueryService.canReadCollection(userId, collection)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 읽기 가능한 문서 ID만 선별해 숨김 문서가 Content와 전체 개수 모두에 포함되지 않게 한다.
        Pageable pageable = PageRequest.of(page, size, COLLECTION_DOCUMENT_SORT);
        List<Long> readableDocumentIds = documentRepository.findReadableDocumentIdsInCollection(
                userId,
                collectionId,
                LISTABLE_DOCUMENT_STATUSES
        );
        if (readableDocumentIds.isEmpty()) {
            return PageResponse.from(Page.empty(pageable), List.of());
        }

        // 3. Transaction 안에서 현재 버전과 추가 이력을 공개 DTO로 변환한다.
        Page<CollectionDocument> collectionDocuments = collectionDocumentRepository.findReadableDocuments(
                collectionId,
                readableDocumentIds,
                pageable
        );
        List<CollectionDocumentListItemResponse> content = collectionDocuments.getContent().stream()
                .map(collectionConverter::toDocumentListItemResponse)
                .toList();
        return PageResponse.from(collectionDocuments, content);
    }
}
