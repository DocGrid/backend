package com.opensource.docgrid.domain.collection.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionRow;
import com.opensource.docgrid.domain.document.converter.DocumentSummaryConverter;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 컬렉션과 컬렉션-문서 매핑 Entity를 공개 응답 DTO로 변환한다.
 * 문서 목록 응답은 읽기 권한 검증이 끝난 매핑만 전달받는다.
 */
@Component
@RequiredArgsConstructor
public class CollectionConverter {

    private final DocumentSummaryConverter documentSummaryConverter;

    // getChildren()처럼 여러 건을 한 번에 변환할 때 쓴다 — owner는 LAZY라 이름까지 필요하면
    // 호출자가 batch 조회한 이름을 toResponse(collection, ownerName)로 넘겨야 한다.
    public CollectionResponse toResponse(DocumentCollection collection) {
        return toResponse(collection, null);
    }

    // getCollection() 단건 상세처럼 owner 하나만 lazy-load해도 되는 경우에 쓴다.
    public CollectionResponse toResponse(DocumentCollection collection, String ownerName) {
        Long parentId = collection.getParentCollection() != null
                ? collection.getParentCollection().getId()
                : null;

        return new CollectionResponse(
                collection.getId(),
                collection.getName(),
                collection.getDescription(),
                collection.getOwner().getId(),
                ownerName,
                parentId,
                collection.getVisibility(),
                collection.getStatus(),
                collection.getCreatedAt()
        );
    }

    // findReadableCollections 네이티브 쿼리 프로젝션 결과를 그대로 변환 (owner 엔티티를 거치지 않음)
    public CollectionResponse toResponse(CollectionRow row) {
        return new CollectionResponse(
                row.getCollectionId(),
                row.getName(),
                row.getDescription(),
                row.getOwnerUserId(),
                row.getOwnerName(),
                row.getParentCollectionId(),
                VisibilityType.valueOf(row.getVisibility()),
                CollectionStatus.valueOf(row.getStatus()),
                row.getCreatedAt()
        );
    }

    public CollectionDocumentResponse toDocumentResponse(CollectionDocument cd) {
        Long addedById = cd.getAddedBy() != null ? cd.getAddedBy().getId() : null;

        return new CollectionDocumentResponse(
                cd.getCollection().getId(),
                cd.getDocument().getId(),
                addedById,
                cd.getAddedAt()
        );
    }

    public CollectionDocumentListItemResponse toDocumentListItemResponse(CollectionDocument collectionDocument) {
        User addedBy = collectionDocument.getAddedBy();

        return new CollectionDocumentListItemResponse(
                collectionDocument.getCollection().getId(),
                documentSummaryConverter.toResponse(collectionDocument.getDocument()),
                addedBy != null ? addedBy.getId() : null,
                addedBy != null ? addedBy.getName() : null,
                collectionDocument.getAddedAt()
        );
    }
}
