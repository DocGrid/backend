package com.opensource.docgrid.domain.user.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.dto.response.DepartmentResponse;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class DepartmentQueryService {

    private final DepartmentRepository departmentRepository;

    public List<DepartmentResponse> getActiveDepartments() {
        return departmentRepository.findAllByStatus(CommonStatus.ACTIVE)
                .stream()
                .map(DepartmentResponse::from)
                .toList();
    }
}
