package com.opensource.docgrid.domain.collection.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;

public class CollectionFixture {

    public static final Long USER_ID = 10L;
    public static final Long COLLECTION_ID = 1L;
    public static final Long DOCUMENT_ID = 5L;
    public static final String COLLECTION_NAME = "테스트 컬렉션";
    public static final String COLLECTION_DESCRIPTION = "테스트 컬렉션 설명";
    public static final String OWNER_NAME = "소유자";

    private CollectionFixture() {
    }

    public static User createOwner() {
        User user = User.builder()
                .email("owner@test.com")
                .passwordHash("hash")
                .name(OWNER_NAME)
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    public static User createOtherUser() {
        User user = User.builder()
                .email("other@test.com")
                .passwordHash("hash")
                .name("다른사용자")
                .status(UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(user, "id", 99L);
        return user;
    }

    public static DocumentCollection createCollection(User owner) {
        DocumentCollection collection = DocumentCollection.builder()
                .owner(owner)
                .name(COLLECTION_NAME)
                .description(COLLECTION_DESCRIPTION)
                .visibility(VisibilityType.PRIVATE)
                .status(CollectionStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(collection, "id", COLLECTION_ID);
        return collection;
    }

    public static DocumentCollection createCollection() {
        return createCollection(createOwner());
    }

    public static DocumentCollection createChildCollection(User owner, DocumentCollection parent, Long childId) {
        DocumentCollection child = DocumentCollection.builder()
                .owner(owner)
                .parentCollection(parent)
                .name("하위 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .status(CollectionStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(child, "id", childId);
        return child;
    }

    public static Document createDocument(User owner) {
        Document document = Document.builder()
                .owner(owner)
                .title("테스트 문서")
                .documentType(DocumentType.PDF)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED)
                .visibility(VisibilityType.PRIVATE)
                .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }

    public static CollectionResponse createCollectionResponse() {
        return new CollectionResponse(
                COLLECTION_ID,
                COLLECTION_NAME,
                COLLECTION_DESCRIPTION,
                USER_ID,
                OWNER_NAME,
                null,
                VisibilityType.PRIVATE,
                CollectionStatus.ACTIVE,
                LocalDateTime.now()
        );
    }

    public static CollectionDocumentResponse createCollectionDocumentResponse() {
        return new CollectionDocumentResponse(
                COLLECTION_ID,
                DOCUMENT_ID,
                USER_ID,
                LocalDateTime.now()
        );
    }
}
