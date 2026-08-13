package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncAdminAction;
import com.opensource.docgrid.domain.sync.enums.SyncAdminActionType;
import com.opensource.docgrid.domain.sync.enums.SyncAdminTargetType;
import com.opensource.docgrid.domain.sync.repository.SyncAdminActionRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 Sync 명령과 같은 Transaction에서 append-only 감사 Action을 기록한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SyncAdminActionWriter {

    private final SyncAdminActionRepository syncAdminActionRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public SyncAdminAction record(
        Long adminUserId,
        SyncAdminActionType actionType,
        SyncAdminTargetType targetType,
        String targetId,
        String reason,
        String metadataJson
    ) {
        return syncAdminActionRepository.save(
            SyncAdminAction.builder()
                .actionId(UUID.randomUUID())
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .adminUser(userRepository.getReferenceById(adminUserId))
                .reason(reason)
                .metadataJson(metadataJson)
                .occurredAt(LocalDateTime.now(clock))
                .build()
        );
    }
}
