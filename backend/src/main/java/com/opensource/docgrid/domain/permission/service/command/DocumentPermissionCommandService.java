package com.opensource.docgrid.domain.permission.service.command;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.sync.enums.SyncPermissionOperation;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class DocumentPermissionCommandService {

    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final UserDocumentAccessCacheService cacheService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionConverter permissionConverter;
    private final PermissionQueryService permissionQueryService;
    private final SyncEventWriter syncEventWriter;

    // 문서 단건 예외 권한 부여
    public DocumentPermissionResponse grantPermission(Long documentId, Long grantorId,
                                                      GrantPermissionRequest request) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (!permissionQueryService.canAdminDocument(grantorId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        validateTargetType(request);

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
        User grantor = userRepository.getReferenceById(grantorId);

        DocumentPermission permission = DocumentPermission.builder()
                .document(document)
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

        documentPermissionRepository.save(permission);

        if (request.targetType() == PermissionTargetType.USER) {
            cacheService.grantUserPermission(targetUser, document,
                    permissions[0], permissions[1], permissions[2],
                    AccessSourceType.DIRECT_DOCUMENT_PERMISSION, permission.getId(), request.expiresAt());
        }

        // 권한 원장과 같은 Transaction에 캐시 재투영 의도를 남겨 후속 누락을 복구할 수 있게 한다.
        syncEventWriter.recordPermissionCacheRefresh(
            AccessSourceType.DIRECT_DOCUMENT_PERMISSION,
            permission.getId(),
            SyncPermissionOperation.GRANTED
        );

        return permissionConverter.toDocumentPermissionResponse(permission);
    }

    // 문서 단건 권한 회수
    public void revokePermission(Long documentId, Long permissionId, Long revokerId) {
        DocumentPermission permission = documentPermissionRepository.findById(permissionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_PERMISSION_NOT_FOUND));

        if (!permission.getDocument().getId().equals(documentId)) {
            throw new DocGridException(ErrorCode.DOCUMENT_PERMISSION_NOT_FOUND);
        }

        if (!permissionQueryService.canAdminDocument(revokerId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        if (permission.getTargetType() == PermissionTargetType.USER) {
            cacheService.revokeUserPermission(permission.getUser().getId(), documentId,
                    AccessSourceType.DIRECT_DOCUMENT_PERMISSION, permissionId);
        }

        documentPermissionRepository.delete(permission);
        syncEventWriter.recordPermissionCacheRefresh(
            AccessSourceType.DIRECT_DOCUMENT_PERMISSION,
            permissionId,
            SyncPermissionOperation.REVOKED
        );
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

    // PermissionType → canRead/canWrite/canAdmin 변환
    private boolean[] resolvePermissions(PermissionType type) {
        return switch (type) {
            case READ  -> new boolean[]{true, false, false};
            case WRITE -> new boolean[]{true, true, false};
            case ADMIN -> new boolean[]{true, true, true};
        };
    }
}
