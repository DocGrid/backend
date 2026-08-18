package com.opensource.docgrid.domain.collection.repository;

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
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import jakarta.persistence.EntityManager;

/**
 * 컬렉션 트리(부모-자식) 재귀 쿼리와 직계 자식 조회를 3단 트리(root→child→grandchild)로 검증한다.
 * findReadableCollectionIds의 owner/PUBLIC 노출, keyword 필터, 부모 컬렉션으로부터의 DEPARTMENT 권한
 * 상속도 함께 검증한다.
 * 이동/수정 API가 없어 순환 참조가 API상 불가능하므로 순환 참조 케이스는 검증하지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("컬렉션 트리 Repository 테스트")
class CollectionTreeRepositoryTest {

    @Autowired private CollectionRepository collectionRepository;
    @Autowired private CollectionDocumentRepository collectionDocumentRepository;
    @Autowired private CollectionPermissionRepository collectionPermissionRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("findAncestorIdsInclusive는 자기 자신부터 최상위 조상까지 전부 반환한다")
    void findAncestorIdsInclusive_returnsSelfAndAllAncestors() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        List<Long> ancestorsOfGrandchild = collectionRepository.findAncestorIdsInclusive(grandchild.getId());
        List<Long> ancestorsOfRoot = collectionRepository.findAncestorIdsInclusive(root.getId());

        assertThat(ancestorsOfGrandchild).containsExactlyInAnyOrder(root.getId(), child.getId(), grandchild.getId());
        assertThat(ancestorsOfRoot).containsExactly(root.getId());
    }

    @Test
    @DisplayName("findDescendantIdsInclusive는 자기 자신부터 모든 후손까지 전부 반환한다")
    void findDescendantIdsInclusive_returnsSelfAndAllDescendants() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        List<Long> descendantsOfRoot = collectionRepository.findDescendantIdsInclusive(root.getId());
        List<Long> descendantsOfGrandchild = collectionRepository.findDescendantIdsInclusive(grandchild.getId());

        assertThat(descendantsOfRoot).containsExactlyInAnyOrder(root.getId(), child.getId(), grandchild.getId());
        assertThat(descendantsOfGrandchild).containsExactly(grandchild.getId());
    }

    @Test
    @DisplayName("findEffectiveCollectionIdsForDocument는 문서가 속한 컬렉션과 그 조상 전체를 반환한다")
    void findEffectiveCollectionIdsForDocument_returnsContainingCollectionsAndAncestors() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        Document document = saveDocument(owner);
        addToCollection(child, document, owner);
        flushAndClear();

        List<Long> effectiveIds = collectionRepository.findEffectiveCollectionIdsForDocument(document.getId());

        assertThat(effectiveIds).containsExactlyInAnyOrder(root.getId(), child.getId());
    }

    @Test
    @DisplayName("findEffectiveCollectionIdsForDocument는 문서가 어느 컬렉션에도 속하지 않으면 빈 목록을 반환한다")
    void findEffectiveCollectionIdsForDocument_returnsEmpty_whenDocumentInNoCollection() {
        User owner = saveOwner();
        Document document = saveDocument(owner);
        flushAndClear();

        List<Long> effectiveIds = collectionRepository.findEffectiveCollectionIdsForDocument(document.getId());

        assertThat(effectiveIds).isEmpty();
    }

    @Test
    @DisplayName("findAllByParentCollectionIdAndStatus는 직계 자식만 반환하고 손자는 포함하지 않는다")
    void findAllByParentCollectionIdAndStatus_returnsDirectChildrenOnly() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        List<DocumentCollection> children = collectionRepository.findAllByParentCollectionIdAndStatus(
                root.getId(), CollectionStatus.ACTIVE
        );

        assertThat(children).extracting(DocumentCollection::getId).containsExactly(child.getId());
        assertThat(children).extracting(DocumentCollection::getId).doesNotContain(grandchild.getId());
    }

    @Test
    @DisplayName("findReadableCollectionIds는 owner의 PRIVATE 컬렉션은 owner에게만, PUBLIC 컬렉션은 누구에게나 보여준다")
    void findReadableCollectionIds_ownerAndPublic() {
        User owner = saveOwner();
        User stranger = saveOwner();
        DocumentCollection privateCollection = saveCollection(owner, null);
        DocumentCollection publicCollection = collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .name("공개 컬렉션")
                .visibility(VisibilityType.PUBLIC)
                .build()
        );
        flushAndClear();

        List<Long> ownerReadable = collectionRepository.findReadableCollectionIds(owner.getId(), null);
        List<Long> strangerReadable = collectionRepository.findReadableCollectionIds(stranger.getId(), null);

        assertThat(ownerReadable).contains(privateCollection.getId(), publicCollection.getId());
        assertThat(strangerReadable).contains(publicCollection.getId());
        assertThat(strangerReadable).doesNotContain(privateCollection.getId());
    }

    @Test
    @DisplayName("findReadableCollectionIds는 keyword가 있으면 이름·설명에 부분일치하는 컬렉션만 반환한다")
    void findReadableCollectionIds_filtersByKeyword() {
        User owner = saveOwner();
        DocumentCollection matching = collectionRepository.save(
            DocumentCollection.builder().owner(owner).name("개발 문서").description("백엔드 관련").visibility(VisibilityType.PRIVATE).build()
        );
        DocumentCollection nonMatching = collectionRepository.save(
            DocumentCollection.builder().owner(owner).name("디자인 자료").description("UI 관련").visibility(VisibilityType.PRIVATE).build()
        );
        flushAndClear();

        List<Long> result = collectionRepository.findReadableCollectionIds(owner.getId(), "개발");

        assertThat(result).contains(matching.getId());
        assertThat(result).doesNotContain(nonMatching.getId());
    }

    @Test
    @DisplayName("findReadableCollectionIds는 부모 컬렉션에 부여된 DEPARTMENT 권한을 자식 컬렉션까지 상속해서 보여준다")
    void findReadableCollectionIds_inheritsDepartmentPermissionFromParent() {
        Department department = departmentRepository.save(
            Department.builder().name("컬렉션목록 테스트 부서").code("CL-DEPT-" + UUID.randomUUID()).status(CommonStatus.ACTIVE).build()
        );
        User owner = saveOwner();
        User deptMember = userRepository.save(
            User.builder()
                .department(department)
                .email("collection-tree-dept-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("컬렉션목록 테스트 부서원")
                .status(UserStatus.ACTIVE)
                .build()
        );
        DocumentCollection parent = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, parent);
        collectionPermissionRepository.save(
            CollectionPermission.builder()
                .collection(parent)
                .targetType(PermissionTargetType.DEPARTMENT)
                .department(department)
                .permissionType(PermissionType.READ)
                .canRead(true)
                .canWrite(false)
                .canAdmin(false)
                .grantedBy(owner)
                .grantedAt(LocalDateTime.now())
                .build()
        );
        flushAndClear();

        List<Long> readable = collectionRepository.findReadableCollectionIds(deptMember.getId(), null);

        assertThat(readable).contains(parent.getId(), child.getId());
    }

    @Test
    @DisplayName("findReadableCollectionIds는 부모 컬렉션에 부여된 ROLE 권한을 자식 컬렉션까지 상속해서 보여준다")
    void findReadableCollectionIds_inheritsRolePermissionFromParent() {
        Role role = roleRepository.save(
            Role.builder().name("컬렉션목록 테스트 역할").code("CL-ROLE-" + UUID.randomUUID()).build()
        );
        User owner = saveOwner();
        User roleMember = userRepository.save(
            User.builder()
                .email("collection-tree-role-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("컬렉션목록 테스트 역할 보유자")
                .status(UserStatus.ACTIVE)
                .build()
        );
        userRoleRepository.save(
            UserRole.builder().user(roleMember).role(role).assignedAt(LocalDateTime.now()).build()
        );
        DocumentCollection parent = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, parent);
        collectionPermissionRepository.save(
            CollectionPermission.builder()
                .collection(parent)
                .targetType(PermissionTargetType.ROLE)
                .role(role)
                .permissionType(PermissionType.READ)
                .canRead(true)
                .canWrite(false)
                .canAdmin(false)
                .grantedBy(owner)
                .grantedAt(LocalDateTime.now())
                .build()
        );
        flushAndClear();

        List<Long> readable = collectionRepository.findReadableCollectionIds(roleMember.getId(), null);

        assertThat(readable).contains(parent.getId(), child.getId());
    }

    private User saveOwner() {
        return userRepository.save(
            User.builder()
                .email("collection-tree-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("컬렉션 트리 테스트 사용자")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private DocumentCollection saveCollection(User owner, DocumentCollection parent) {
        return collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .parentCollection(parent)
                .name("컬렉션 트리 테스트 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private Document saveDocument(User owner) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title("컬렉션 트리 테스트 문서")
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED)
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
