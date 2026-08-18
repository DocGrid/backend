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
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
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
    @Autowired private CollectionPermissionRepository collectionPermissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
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
        DocumentCollection collection = saveCollection(owner, null);
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

    @Test
    @DisplayName("부모 컬렉션에 DEPARTMENT 권한이 있으면 자식 컬렉션의 문서도 목록과 컬렉션 조회에서 함께 조회된다 (상속)")
    void findReadableDocumentIds_includesDocumentInChildCollection_whenParentHasDepartmentPermission() {
        Department department = saveDepartment();
        User owner = saveOwner();
        User deptMember = saveUserInDepartment(department);
        Document document = saveDocument(owner, DocumentStatus.INDEXED);

        DocumentCollection parent = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, parent);
        addToCollection(child, document, owner);
        grantDepartmentReadPermission(parent, department, owner);
        flushAndClear();

        List<Long> readableIds = documentRepository.findReadableDocumentIds(deptMember.getId(), INDEXED_ONLY);
        List<Long> readableIdsInChildCollection = documentRepository.findReadableDocumentIdsInCollection(
                deptMember.getId(), child.getId(), INDEXED_ONLY
        );

        assertThat(readableIds).contains(document.getId());
        assertThat(readableIdsInChildCollection).containsExactly(document.getId());
    }

    @Test
    @DisplayName("컬렉션에 직접 부여된 권한만 있으면(부모 없음) 기존과 동일하게 조회된다 (회귀)")
    void findReadableDocumentIds_includesDocument_whenDirectDepartmentPermission_noParent() {
        Department department = saveDepartment();
        User owner = saveOwner();
        User deptMember = saveUserInDepartment(department);
        Document document = saveDocument(owner, DocumentStatus.INDEXED);

        DocumentCollection collection = saveCollection(owner, null);
        addToCollection(collection, document, owner);
        grantDepartmentReadPermission(collection, department, owner);
        flushAndClear();

        List<Long> readableIds = documentRepository.findReadableDocumentIds(deptMember.getId(), INDEXED_ONLY);

        assertThat(readableIds).contains(document.getId());
    }

    private Department saveDepartment() {
        return departmentRepository.save(
            Department.builder()
                .name("읽기 가능 문서 테스트 부서")
                .code("RID-DEPT-" + UUID.randomUUID())
                .status(CommonStatus.ACTIVE)
                .build()
        );
    }

    private User saveUserInDepartment(Department department) {
        return userRepository.save(
            User.builder()
                .department(department)
                .email("readable-ids-dept-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("읽기 가능 문서 테스트 부서원")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private void grantDepartmentReadPermission(DocumentCollection collection, Department department, User grantedBy) {
        collectionPermissionRepository.save(
            CollectionPermission.builder()
                .collection(collection)
                .targetType(PermissionTargetType.DEPARTMENT)
                .department(department)
                .permissionType(PermissionType.READ)
                .canRead(true)
                .canWrite(false)
                .canAdmin(false)
                .grantedBy(grantedBy)
                .grantedAt(LocalDateTime.now())
                .build()
        );
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

    private DocumentCollection saveCollection(User owner, DocumentCollection parent) {
        return collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .parentCollection(parent)
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
