package com.opensource.docgrid.domain.collection.service.query;

import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import com.opensource.docgrid.domain.collection.repository.CollectionRow;
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
     *
     * <p>"전체를 찾은 뒤 페이지를 자르는" 2단계 조회 대신, 페이지 내용과 전체 개수를 한 번의
     * 쿼리로 함께 얻는다(COUNT(*) OVER()) — 콘텐츠 쿼리와 count 쿼리를 따로 두면 권한 판단이
     * 두 번 계산되는 걸 피하기 위함이다.
     */
    public PageResponse<CollectionResponse> getCollections(Long userId, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, COLLECTION_SORT);

        // 컬렉션 목록과 전체 개수를 한 번에 조회 (CollectionRow 프로젝션)
        List<CollectionRow> rows = collectionRepository.findReadableCollections(
                userId, keyword, pageable.getPageSize(), pageable.getOffset());

        long totalElements = rows.isEmpty() ? 0 : rows.get(0).getTotalCount();

        List<CollectionResponse> content = rows.stream()
                .map(collectionConverter::toResponse)
                .toList();
        Page<CollectionResponse> resultPage = new PageImpl<>(content, pageable, totalElements);
        return PageResponse.from(resultPage, content);
    }

    /**
     * 직계 자식 컬렉션 목록 조회 — 부모 읽기 권한 확인 후, 권한 조건이 반영된 자식만 조회한다.
     * 자식별 읽기 권한 필터는 findReadableChildren 쿼리 안에서 함께 처리되므로, 자식 개수만큼
     * canReadCollection을 반복 호출하지 않는다(부모 상속 여부는 자식 전체가 공유하는 값이라
     * 쿼리 안에서 한 번만 계산됨).
     */
    public List<CollectionResponse> getChildren(Long userId, Long collectionId) {
        DocumentCollection parent = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!permissionQueryService.canReadCollection(userId, parent)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 부모 읽기 권한이 있는 경우에만, 자식 컬렉션 중 읽기 가능한 것들을 조회한다.
        return collectionRepository.findReadableChildren(collectionId, userId)
                .stream()
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
