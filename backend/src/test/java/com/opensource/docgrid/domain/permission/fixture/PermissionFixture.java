package com.opensource.docgrid.domain.permission.fixture;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.CommonStatus;

public class PermissionFixture {

    public static final Long PERMISSION_ID = 30L;
    public static final Long ROLE_ID = 2L;
    public static final Long DEPARTMENT_ID = 3L;

    private PermissionFixture() {
    }

    public static Role createRole() {
        Role role = Role.builder()
                .code("USER")
                .name("일반 사용자")
                .build();
        ReflectionTestUtils.setField(role, "id", ROLE_ID);
        return role;
    }

    public static Department createDepartment() {
        Department department = Department.builder()
                .name("개발팀")
                .status(CommonStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(department, "id", DEPARTMENT_ID);
        return department;
    }

    public static CollectionPermission createCollectionPermission(DocumentCollection collection, User user) {
        CollectionPermission permission = CollectionPermission.builder()
                .collection(collection)
                .targetType(PermissionTargetType.USER)
                .user(user)
                .permissionType(PermissionType.READ)
                .canRead(true)
                .canWrite(false)
                .canAdmin(false)
                .grantedBy(user)
                .grantedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(permission, "id", PERMISSION_ID);
        return permission;
    }

    public static DocumentPermission createDocumentPermission(Document document, User user) {
        DocumentPermission permission = DocumentPermission.builder()
                .document(document)
                .targetType(PermissionTargetType.USER)
                .user(user)
                .permissionType(PermissionType.READ)
                .canRead(true)
                .canWrite(false)
                .canAdmin(false)
                .grantedBy(user)
                .grantedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(permission, "id", PERMISSION_ID);
        return permission;
    }

    public static User createOwner() {
        return CollectionFixture.createOwner();
    }

    public static Document createDocument() {
        return CollectionFixture.createDocument(createOwner());
    }

    public static DocumentCollection createCollection() {
        return CollectionFixture.createCollection(createOwner());
    }
}
