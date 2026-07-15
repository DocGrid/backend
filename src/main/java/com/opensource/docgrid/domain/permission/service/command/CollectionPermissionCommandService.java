package com.opensource.docgrid.domain.permission.service.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class CollectionPermissionCommandService {

    private final CollectionRepository collectionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;
    private final UserDocumentAccessCacheService cacheService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionConverter permissionConverter;

    // 컬렉션 권한 부여
    public CollectionPermissionResponse grantPermission(Long collectionId, Long grantorId,
                                                        GrantPermissionRequest request) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        // TODO: Issue 3 PermissionService 완성 후 canAdminDocument() 로 대체
        if (!collection.getOwner().getId().equals(grantorId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        validateTargetType(request); // targetType과 ID 필드 조합 유효성 검사

        User targetUser = null;
        Role targetRole = null;
        Department targetDepartment = null;

        if (request.targetType() == PermissionTargetType.USER) {
            targetUser = userRepository.findById(request.userId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        } else if (request.targetType() == PermissionTargetType.ROLE) {
            targetRole = roleRepository.findById(request.roleId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_FOUND));
        } else {
            targetDepartment = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.DEPARTMENT_NOT_FOUND));
        }

        boolean[] permissions = resolvePermissions(request.permissionType());
        User grantor = userRepository.getReferenceById(grantorId); // 권한 부여자 정보 조회

        CollectionPermission permission = CollectionPermission.builder()
                .collection(collection)
                .targetType(request.targetType())
                .user(targetUser)
                .role(targetRole)
                .department(targetDepartment)
                .permissionType(request.permissionType())
                .canRead(permissions[0])
                .canWrite(permissions[1])
                .canAdmin(permissions[2])
                .grantedBy(grantor)
                .grantedAt(LocalDateTime.now())
                .expiresAt(request.expiresAt())
                .build();

        collectionPermissionRepository.save(permission);

        if (request.targetType() == PermissionTargetType.USER) {
            updateCacheForCollection(collectionId, targetUser, permissions, permission.getId(), request.expiresAt());
        }

        return permissionConverter.toCollectionPermissionResponse(permission);
    }

    // 컬렉션 권한 회수
    public void revokePermission(Long collectionId, Long permissionId, Long revokerId) {
        CollectionPermission permission = collectionPermissionRepository.findById(permissionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_PERMISSION_NOT_FOUND));

        DocumentCollection collection = permission.getCollection();
        if (!collection.getId().equals(collectionId)) {
            throw new DocGridException(ErrorCode.COLLECTION_PERMISSION_NOT_FOUND);
        }

        // TODO: Issue 3 PermissionService 완성 후 canAdminDocument() 로 대체
        if (!collection.getOwner().getId().equals(revokerId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        if (permission.getTargetType() == PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, permissionId);
        }

        collectionPermissionRepository.delete(permission);
    }

    // targetType과 ID 필드 조합 유효성 검사
    private void validateTargetType(GrantPermissionRequest request) {
        boolean valid = switch (request.targetType()) {
            case USER -> request.userId() != null && request.roleId() == null && request.departmentId() == null;
            case ROLE -> request.roleId() != null && request.userId() == null && request.departmentId() == null;
            case DEPARTMENT -> request.departmentId() != null && request.userId() == null && request.roleId() == null;
        };
        if (!valid) {
            throw new DocGridException(ErrorCode.INVALID_TARGET_TYPE);
        }
    }

    // PermissionType → canRead/canWrite/canAdmin 변환 (WRITE는 READ 포함, ADMIN은 전체 포함)
    private boolean[] resolvePermissions(PermissionType type) {
        return switch (type) {
            case READ  -> new boolean[]{true, false, false};
            case WRITE -> new boolean[]{true, true, false};
            case ADMIN -> new boolean[]{true, true, true};
        };
    }

    // USER 권한 부여 시 컬렉션 내 모든 문서에 캐시 일괄 갱신 (N+1 방지)
    private void updateCacheForCollection(Long collectionId, User targetUser, boolean[] permissions,
                                          Long sourceId, LocalDateTime expiresAt) {
        List<Document> documents = collectionDocumentRepository.findAllByCollectionId(collectionId)
                .stream().map(CollectionDocument::getDocument).toList();
        cacheService.bulkGrantUserPermission(targetUser, documents,
                permissions[0], permissions[1], permissions[2],
                AccessSourceType.DIRECT_COLLECTION_PERMISSION, sourceId, expiresAt);
    }
}
