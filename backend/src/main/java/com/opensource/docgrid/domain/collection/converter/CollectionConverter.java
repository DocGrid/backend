package com.opensource.docgrid.domain.collection.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;

@Component
public class CollectionConverter {

    public CollectionResponse toResponse(DocumentCollection collection) {
        Long parentId = collection.getParentCollection() != null
                ? collection.getParentCollection().getId()
                : null;

        return new CollectionResponse(
                collection.getId(),
                collection.getName(),
                collection.getDescription(),
                collection.getOwner().getId(),
                parentId,
                collection.getVisibility(),
                collection.getStatus(),
                collection.getCreatedAt()
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
}
