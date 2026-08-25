package com.opensource.docgrid.domain.permission.service.query;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.permission.dto.response.PermissionTargetUserResponse;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 리소스 ADMIN 권한을 확인한 뒤 권한 부여 대상으로 선택할 활성 사용자를 제한적으로 검색한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PermissionTargetUserQueryService {

    private static final Pageable FIRST_USERS_BY_NAME = PageRequest.of(
        0,
        20,
        Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))
    );

    private final PermissionQueryService permissionQueryService;
    private final UserRepository userRepository;

    public List<PermissionTargetUserResponse> searchForDocument(Long requesterId, Long documentId, String keyword) {
        // 1. 문서 권한을 부여할 수 있는 사용자에게만 조직 사용자 검색 결과를 공개한다.
        if (!permissionQueryService.canAdminDocument(requesterId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 선택 UI에 필요한 활성 사용자만 이름순으로 제한해 반환한다.
        return searchActiveUsers(keyword);
    }

    public List<PermissionTargetUserResponse> searchForCollection(
        Long requesterId,
        Long collectionId,
        String keyword
    ) {
        // 1. 컬렉션 권한을 부여할 수 있는 사용자에게만 조직 사용자 검색 결과를 공개한다.
        if (!permissionQueryService.canAdminCollection(requesterId, collectionId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 문서 검색과 같은 활성 사용자 계약을 사용해 대상 선택 방식이 달라지지 않게 한다.
        return searchActiveUsers(keyword);
    }

    private List<PermissionTargetUserResponse> searchActiveUsers(String keyword) {
        String normalizedKeyword = keyword.trim();
        if (normalizedKeyword.isEmpty()) return List.of();

        return userRepository.findAdminUsers(
            normalizedKeyword,
            null,
            UserStatus.ACTIVE,
            UserStatus.DELETED,
            FIRST_USERS_BY_NAME
        ).getContent().stream()
            .map(PermissionTargetUserResponse::from)
            .toList();
    }
}
