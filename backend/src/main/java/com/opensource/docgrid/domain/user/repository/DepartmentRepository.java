package com.opensource.docgrid.domain.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.enums.CommonStatus;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByStatus(CommonStatus status);
}
