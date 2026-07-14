package com.opensource.docgrid.domain.auth.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.auth.dto.response.MeResponse;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class AuthQueryService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public MeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        List<String> roles = userRoleRepository.findAllWithRoleByUserId(userId).stream()
                .map(ur -> ur.getRole().getCode())
                .toList();

        return MeResponse.of(user, roles);
    }
}
