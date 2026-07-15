package com.opensource.docgrid.domain.permission.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.permission.entity.DocumentPermission;

public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, Long> {
}
