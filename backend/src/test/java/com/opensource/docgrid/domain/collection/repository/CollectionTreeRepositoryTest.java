package com.opensource.docgrid.domain.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
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
 * findReadableCollections의 owner/PUBLIC 노출, keyword 필터, 페이지네이션/totalCount, 부모 컬렉션으로부터의
 * ROLE·DEPARTMENT 권한 상속과, findReadableChildren의 권한 필터·다단계 상속도 함께 검증한다.
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
    @DisplayName("insertClosureForNewCollection은 자기 자신(depth 0)과 모든 조상을 depth와 함께 넣는다")
    void insertClosureForNewCollection_materializesSelfAndAncestors() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        assertThat(closureAncestorsWithDepth(grandchild.getId()))
            .containsOnly(entry(grandchild.getId(), 0), entry(child.getId(), 1), entry(root.getId(), 2));
        assertThat(closureAncestorsWithDepth(root.getId()))
            .containsOnly(entry(root.getId(), 0));
    }

    @Test
    @DisplayName("deleteClosureByDescendantIds는 대상 서브트리의 closure 행을 모두 제거한다")
    void deleteClosureByDescendantIds_removesSubtreeRows() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        collectionRepository.deleteClosureByDescendantIds(List.of(child.getId(), grandchild.getId()));
        flushAndClear();

        assertThat(closureAncestorsWithDepth(root.getId())).containsOnly(entry(root.getId(), 0));
        assertThat(closureAncestorsWithDepth(child.getId())).isEmpty();
        assertThat(closureAncestorsWithDepth(grandchild.getId())).isEmpty();
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
    @DisplayName("findReadableChildren는 직계 자식만 반환하고 손자는 포함하지 않는다")
    void findReadableChildren_returnsDirectChildrenOnly() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        DocumentCollection child = saveCollection(owner, root);
        DocumentCollection grandchild = saveCollection(owner, child);
        flushAndClear();

        List<Long> children = readableChildrenIds(root.getId(), owner.getId());

        assertThat(children).containsExactly(child.getId());
        assertThat(children).doesNotContain(grandchild.getId());
    }

    @Test
    @DisplayName("findReadableChildren는 읽기 권한 없는 자식은 제외한다")
    void findReadableChildren_excludesChild_whenNoPermission() {
        User owner = saveOwner();
        User stranger = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        saveCollection(owner, root); // PRIVATE 자식, stranger는 권한 없음
        flushAndClear();

        List<Long> forStranger = readableChildrenIds(root.getId(), stranger.getId());
        List<Long> forOwner = readableChildrenIds(root.getId(), owner.getId());

        assertThat(forStranger).isEmpty();
        assertThat(forOwner).hasSize(1);
    }

    @Test
    @DisplayName("findReadableChildren는 조부모(2단계 위)에 부여된 ROLE 권한도 상속해서 자식을 보여준다")
    void findReadableChildren_inheritsRolePermissionFromGrandparent() {
        Role role = roleRepository.save(
            Role.builder().name("자식조회 테스트 역할").code("CH-ROLE-" + UUID.randomUUID()).build()
        );
        User owner = saveOwner();
        User roleMember = userRepository.save(
            User.builder()
                .email("children-role-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("자식조회 테스트 역할 보유자")
                .status(UserStatus.ACTIVE)
                .build()
        );
        userRoleRepository.save(
            UserRole.builder().user(roleMember).role(role).assignedAt(LocalDateTime.now()).build()
        );
        DocumentCollection grandparent = saveCollection(owner, null);
        DocumentCollection parent = saveCollection(owner, grandparent);
        DocumentCollection child = saveCollection(owner, parent);
        collectionPermissionRepository.save(
            CollectionPermission.builder()
                .collection(grandparent)
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

        // parent 자체는 grandparent로부터 상속받아 읽을 수 있고, parent의 자식(child)도 같은 체인으로 상속받는다.
        List<Long> childrenOfParent = readableChildrenIds(parent.getId(), roleMember.getId());

        assertThat(childrenOfParent).containsExactly(child.getId());
    }

    @Test
    @DisplayName("findReadableCollections는 owner의 PRIVATE 컬렉션은 owner에게만, PUBLIC 컬렉션은 누구에게나 보여준다")
    void findReadableCollections_ownerAndPublic() {
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

        List<Long> ownerReadable = readableIds(owner.getId(), null);
        List<Long> strangerReadable = readableIds(stranger.getId(), null);

        assertThat(ownerReadable).contains(privateCollection.getId(), publicCollection.getId());
        assertThat(strangerReadable).contains(publicCollection.getId());
        assertThat(strangerReadable).doesNotContain(privateCollection.getId());
    }

    @Test
    @DisplayName("findReadableCollections는 keyword가 있으면 이름·설명에 부분일치하는 컬렉션만 반환한다")
    void findReadableCollections_filtersByKeyword() {
        User owner = saveOwner();
        DocumentCollection matching = collectionRepository.save(
            DocumentCollection.builder().owner(owner).name("개발 문서").description("백엔드 관련").visibility(VisibilityType.PRIVATE).build()
        );
        DocumentCollection nonMatching = collectionRepository.save(
            DocumentCollection.builder().owner(owner).name("디자인 자료").description("UI 관련").visibility(VisibilityType.PRIVATE).build()
        );
        flushAndClear();

        List<Long> result = readableIds(owner.getId(), "개발");

        assertThat(result).contains(matching.getId());
        assertThat(result).doesNotContain(nonMatching.getId());
    }

    @Test
    @DisplayName("findReadableCollections는 limit/offset으로 페이지를 나누고, 모든 행에 동일한 totalCount를 함께 반환한다")
    void findReadableCollections_paginatesAndReturnsTotalCountOnEveryRow() {
        User owner = saveOwner();
        for (int i = 0; i < 3; i++) {
            saveCollection(owner, null);
        }
        flushAndClear();

        List<CollectionRow> firstPage = collectionRepository.findReadableCollections(owner.getId(), null, 2, 0L);
        List<CollectionRow> secondPage = collectionRepository.findReadableCollections(owner.getId(), null, 2, 2L);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).allSatisfy(row -> assertThat(row.getTotalCount()).isEqualTo(3));
        assertThat(secondPage.get(0).getTotalCount()).isEqualTo(3);
        // 두 페이지에 중복 없이 전부 다른 컬렉션이 나뉘어 담겨야 한다.
        List<Long> firstPageIds = firstPage.stream().map(CollectionRow::getCollectionId).toList();
        List<Long> secondPageIds = secondPage.stream().map(CollectionRow::getCollectionId).toList();
        assertThat(firstPageIds).doesNotContainAnyElementsOf(secondPageIds);
    }

    @Test
    @DisplayName("findReadableChildren는 limit/offset으로 페이지를 나누고, 모든 행에 동일한 totalCount와 owner_name을 함께 반환한다")
    void findReadableChildren_paginatesAndReturnsTotalCountAndOwnerName() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        for (int i = 0; i < 3; i++) {
            saveCollection(owner, root);
        }
        flushAndClear();

        List<CollectionRow> firstPage = collectionRepository.findReadableChildren(root.getId(), owner.getId(), 2, 0L);
        List<CollectionRow> secondPage = collectionRepository.findReadableChildren(root.getId(), owner.getId(), 2, 2L);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).allSatisfy(row -> assertThat(row.getTotalCount()).isEqualTo(3));
        assertThat(secondPage.get(0).getTotalCount()).isEqualTo(3);
        // owner_name이 쿼리 안에서 조인돼 채워지는지 (자식마다 owner 재조회하는 N+1 방지 확인)
        assertThat(firstPage).allSatisfy(row -> assertThat(row.getOwnerName()).isEqualTo(owner.getName()));
        List<Long> firstPageIds = firstPage.stream().map(CollectionRow::getCollectionId).toList();
        List<Long> secondPageIds = secondPage.stream().map(CollectionRow::getCollectionId).toList();
        assertThat(firstPageIds).doesNotContainAnyElementsOf(secondPageIds);
    }

    @Test
    @DisplayName("findReadableChildren는 요청한 offset이 마지막 페이지를 넘어가도, countReadableChildren으로 실제 전체 개수를 알 수 있다")
    void countReadableChildren_returnsTotalCount_whenPageBeyondLastPage() {
        User owner = saveOwner();
        DocumentCollection root = saveCollection(owner, null);
        saveCollection(owner, root);
        saveCollection(owner, root);
        flushAndClear();

        List<CollectionRow> beyondLastPage = collectionRepository.findReadableChildren(root.getId(), owner.getId(), 20, 100L);
        long total = collectionRepository.countReadableChildren(root.getId(), owner.getId());

        assertThat(beyondLastPage).isEmpty();
        assertThat(total).isEqualTo(2);
    }

    @Test
    @DisplayName("findReadableCollections는 부모 컬렉션에 부여된 DEPARTMENT 권한을 자식 컬렉션까지 상속해서 보여준다")
    void findReadableCollections_inheritsDepartmentPermissionFromParent() {
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

        List<Long> readable = readableIds(deptMember.getId(), null);

        assertThat(readable).contains(parent.getId(), child.getId());
    }

    @Test
    @DisplayName("findReadableCollections는 부모 컬렉션에 부여된 ROLE 권한을 자식 컬렉션까지 상속해서 보여준다")
    void findReadableCollections_inheritsRolePermissionFromParent() {
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

        List<Long> readable = readableIds(roleMember.getId(), null);

        assertThat(readable).contains(parent.getId(), child.getId());
    }

    // findReadableCollections는 limit/offset을 받는 페이지 조회라, ID만으로 assertThat(...).contains 하던
    // 기존 테스트들이 그대로 동작하도록 넉넉한 limit(100)으로 감싸는 헬퍼.
    private List<Long> readableIds(Long userId, String keyword) {
        return collectionRepository.findReadableCollections(userId, keyword, 100, 0L)
                .stream()
                .map(CollectionRow::getCollectionId)
                .toList();
    }

    private List<Long> readableChildrenIds(Long parentId, Long userId) {
        return collectionRepository.findReadableChildren(parentId, userId, 100, 0L)
                .stream()
                .map(CollectionRow::getCollectionId)
                .toList();
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
        DocumentCollection saved = collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .parentCollection(parent)
                .name("컬렉션 트리 테스트 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
        // 운영 코드(CollectionCommandService)와 동일하게 closure table을 갱신한다.
        collectionRepository.insertClosureForNewCollection(
            saved.getId(), parent != null ? parent.getId() : null);
        return saved;
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

    // collection_closure에서 특정 descendant의 (ancestor_id -> depth) 매핑을 직접 읽어 검증한다.
    @SuppressWarnings("unchecked")
    private Map<Long, Integer> closureAncestorsWithDepth(Long descendantId) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT ancestor_id, depth FROM collection_closure WHERE descendant_id = :id")
            .setParameter("id", descendantId)
            .getResultList();
        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return result;
    }
}
