package com.opensource.docgrid.domain.user.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.user.dto.response.RoleResponse;
import com.opensource.docgrid.domain.user.repository.RoleRepository;

import lombok.RequiredArgsConstructor;

/**
 * 역할 목록 조회 서비스.
 *
 * <p>{@link RoleRepository}에서 전체 역할을 읽어 {@link RoleResponse}로 변환하는 책임만 가진다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class RoleQueryService {

    private final RoleRepository roleRepository;

    public List<RoleResponse> getRoles() {
        return roleRepository.findAll()
                .stream()
                .map(RoleResponse::from)
                .toList();
    }
}
