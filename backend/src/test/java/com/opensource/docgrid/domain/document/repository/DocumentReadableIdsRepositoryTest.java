package com.opensource.docgrid.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("읽기 가능 문서 ID Repository 테스트")
class DocumentReadableIdsRepositoryTest {

    private static final List<String> INDEXED_ONLY = List.of(DocumentStatus.INDEXED.name());
    private static final List<String> INDEXED_AND_INDEXING = List.of(
        DocumentStatus.INDEXED.name(),
        DocumentStatus.INDEXING.name()
    );

    @Autowired private DocumentRepository documentRepository;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private CollectionDocumentRepository collectionDocumentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    // seed 데이터에 PUBLIC·INDEXED 문서가 있어 모든 사용자에게 조회되므로, 이 테스트가 만든 문서만 검증한다.
    @Test
    @DisplayName("INDEXED만 요청하면 인덱싱 중인 문서는 제외한다")
    void findReadableDocumentIds_returnsIndexedOnly_when_indexedStatusIsRequested() {
        User owner = saveOwner();
        Document indexed = saveDocument(owner, DocumentStatus.INDEXED);
        Document indexing = saveDocument(owner, DocumentStatus.INDEXING);
        flushAndClear();

        List<Long> result = documentRepository.findReadableDocumentIds(owner.getId(), INDEXED_ONLY);

        assertThat(result).contains(indexed.getId()).doesNotContain(indexing.getId());
    }

    @Test
    @DisplayName("INDEXING을 함께 요청하면 인덱싱 중인 문서도 반환한다")
    void findReadableDocumentIds_returnsProcessingDocument_when_indexingStatusIsRequested() {
        User owner = saveOwner();
        Document indexed = saveDocument(owner, DocumentStatus.INDEXED);
        Document indexing = saveDocument(owner, DocumentStatus.INDEXING);
        flushAndClear();

        List<Long> result = documentRepository.findReadableDocumentIds(owner.getId(), INDEXED_AND_INDEXING);

        assertThat(result).contains(indexed.getId(), indexing.getId());
    }

    @Test
    @DisplayName("soft delete된 문서는 DELETED 상태를 요청해도 제외한다")
    void findReadableDocumentIds_excludesDeletedDocument() {
        User owner = saveOwner();
        Document deleted = saveDocument(owner, DocumentStatus.INDEXED);
        deleted.markDeleted(LocalDateTime.now());
        flushAndClear();

        List<Long> result = documentRepository.findReadableDocumentIds(
            owner.getId(), List.of(DocumentStatus.DELETED.name())
        );

        assertThat(result).doesNotContain(deleted.getId());
    }

    @Test
    @DisplayName("컬렉션 범위 조회도 요청한 상태만 반환한다")
    void findReadableDocumentIdsInCollection_appliesStatusFilter() {
        User owner = saveOwner();
        Document indexed = saveDocument(owner, DocumentStatus.INDEXED);
        Document indexing = saveDocument(owner, DocumentStatus.INDEXING);
        DocumentCollection collection = saveCollection(owner);
        addToCollection(collection, indexed, owner);
        addToCollection(collection, indexing, owner);
        flushAndClear();

        List<Long> indexedOnly = documentRepository.findReadableDocumentIdsInCollection(
            owner.getId(), collection.getId(), INDEXED_ONLY
        );
        List<Long> withIndexing = documentRepository.findReadableDocumentIdsInCollection(
            owner.getId(), collection.getId(), INDEXED_AND_INDEXING
        );

        assertThat(indexedOnly).containsExactly(indexed.getId());
        assertThat(withIndexing).containsExactlyInAnyOrder(indexed.getId(), indexing.getId());
    }

    private User saveOwner() {
        return userRepository.save(
            User.builder()
                .email("readable-ids-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("읽기 가능 문서 테스트 사용자")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private Document saveDocument(User owner, DocumentStatus status) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title("읽기 가능 문서 테스트")
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(status)
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private DocumentCollection saveCollection(User owner) {
        return collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .name("읽기 가능 문서 테스트 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private void addToCollection(DocumentCollection collection, Document document, User addedBy) {
        collectionDocumentRepository.save(
            CollectionDocument.builder()
                .collection(collection)
                .document(document)
                .addedBy(addedBy)
                .addedAt(LocalDateTime.now())
                .build()
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
