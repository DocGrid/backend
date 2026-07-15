package com.opensource.docgrid.domain.permission.service.command;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.entity.UserDocumentAccessCache;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.domain.user.entity.User;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class UserDocumentAccessCacheService {

    private final UserDocumentAccessCacheRepository cacheRepository;

    // USER 권한 캐시 저장 (이미 있으면 갱신, 없으면 신규 insert)
    public void grantUserPermission(User user, Document document,
                                    boolean canRead, boolean canWrite, boolean canAdmin,
                                    AccessSourceType sourceType, Long sourceId,
                                    LocalDateTime expiresAt) {
        cacheRepository.findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
                user.getId(), document.getId(), sourceType, sourceId)
                .ifPresentOrElse(
                        cache -> { // 이미 존재하면 갱신
                            cache.grant(canRead, canWrite, canAdmin, expiresAt);
                        },
                        () -> { // 존재하지 않으면 신규 insert
                            UserDocumentAccessCache cache = UserDocumentAccessCache.builder()
                                    .user(user)
                                    .document(document)
                                    .canRead(canRead)
                                    .canWrite(canWrite)
                                    .canAdmin(canAdmin)
                                    .sourceType(sourceType)
                                    .sourceId(sourceId)
                                    .computedAt(LocalDateTime.now())
                                    .expiresAt(expiresAt)
                                    .build();
                            cacheRepository.save(cache);
                        }
                );
    }

    // USER 권한 캐시 무효화 (invalidated_at 설정)
    public void revokeUserPermission(Long userId, Long documentId,
                                     AccessSourceType sourceType, Long sourceId) {
        cacheRepository.findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
                userId, documentId, sourceType, sourceId)
                .ifPresent(UserDocumentAccessCache::invalidate);
    }
}
