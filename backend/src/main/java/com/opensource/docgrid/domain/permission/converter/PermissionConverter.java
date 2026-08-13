package com.opensource.docgrid.domain.permission.converter;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;

@Component
public class PermissionConverter {

    public CollectionPermissionResponse toCollectionPermissionResponse(CollectionPermission p) {
        return new CollectionPermissionResponse(
                p.getId(),
                p.getCollection().getId(),
                p.getTargetType(),
                p.getUser() != null ? p.getUser().getId() : null,
                p.getRole() != null ? p.getRole().getId() : null,
                p.getDepartment() != null ? p.getDepartment().getId() : null,
                p.getPermissionType(),
                p.isCanRead(),
                p.isCanWrite(),
                p.isCanAdmin(),
                p.getGrantedBy() != null ? p.getGrantedBy().getId() : null,
                p.getGrantedAt(),
                p.getExpiresAt()
        );
    }

    public DocumentPermissionResponse toDocumentPermissionResponse(DocumentPermission p) {
        return new DocumentPermissionResponse(
                p.getId(),
                p.getDocument().getId(),
                p.getTargetType(),
                p.getUser() != null ? p.getUser().getId() : null,
                p.getRole() != null ? p.getRole().getId() : null,
                p.getDepartment() != null ? p.getDepartment().getId() : null,
                p.getPermissionType(),
                p.isCanRead(),
                p.isCanWrite(),
                p.isCanAdmin(),
                p.getGrantedBy() != null ? p.getGrantedBy().getId() : null,
                p.getGrantedAt(),
                p.getExpiresAt()
        );
    }
}
