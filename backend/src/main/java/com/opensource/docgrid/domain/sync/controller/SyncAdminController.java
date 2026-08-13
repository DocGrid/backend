package com.opensource.docgrid.domain.sync.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.sync.dto.request.IgnoreSyncIssueRequest;
import com.opensource.docgrid.domain.sync.dto.request.RunSyncReconciliationRequest;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminActionResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncAdminSummaryResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncEventAdminResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncIssueAdminResponse;
import com.opensource.docgrid.domain.sync.dto.response.SyncReconciliationAdminResponse;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueStatus;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.service.command.SyncAdminCommandService;
import com.opensource.docgrid.domain.sync.service.query.SyncAdminQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * ADMIN 전용 Sync 운영 요약·Event·Issue 조회와 감사 가능한 재시도·복구·무시·검사 API를 제공한다.
 */
@Tag(name = "Admin - Sync", description = "관리자 전용 Outbox와 정합성 Reconciliation 운영 API")
@Validated
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class SyncAdminController {

    private final SyncAdminQueryService syncAdminQueryService;
    private final SyncAdminCommandService syncAdminCommandService;

    @Operation(summary = "Sync 운영 요약 조회")
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SyncAdminSummaryResponse>> getSummary() {
        return ResponseUtils.ok(syncAdminQueryService.getSummary());
    }

    @Operation(summary = "Sync Event 목록 조회")
    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PageResponse<SyncEventAdminResponse>>> getEvents(
        @RequestParam(required = false) SyncEventStatus status,
        @RequestParam(required = false) SyncEventType eventType,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(syncAdminQueryService.getEvents(status, eventType, page, size));
    }

    @Operation(summary = "정합성 Issue 목록 조회")
    @GetMapping(value = "/issues", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<PageResponse<SyncIssueAdminResponse>>> getIssues(
        @RequestParam(required = false) SyncConsistencyIssueStatus status,
        @RequestParam(required = false) SyncConsistencyIssueType issueType,
        @RequestParam(required = false) SyncConsistencySeverity severity,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(syncAdminQueryService.getIssues(status, issueType, severity, page, size));
    }

    @Operation(summary = "최종 실패 Sync Event 재시도")
    @PostMapping(value = "/events/{eventId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SyncAdminActionResponse>> retryEvent(
        @PathVariable UUID eventId,
        @CurrentUser Long adminUserId
    ) {
        return ResponseUtils.ok(syncAdminCommandService.retryEvent(eventId, adminUserId));
    }

    @Operation(summary = "정합성 Issue 안전 복구 요청")
    @PostMapping(value = "/issues/{issueId}/repair", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SyncAdminActionResponse>> repairIssue(
        @PathVariable @Positive Long issueId,
        @CurrentUser Long adminUserId
    ) {
        return ResponseUtils.ok(syncAdminCommandService.repairIssue(issueId, adminUserId));
    }

    @Operation(summary = "정합성 Issue 무시")
    @PostMapping(value = "/issues/{issueId}/ignore", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SyncAdminActionResponse>> ignoreIssue(
        @PathVariable @Positive Long issueId,
        @CurrentUser Long adminUserId,
        @RequestBody @Valid IgnoreSyncIssueRequest request
    ) {
        return ResponseUtils.ok(syncAdminCommandService.ignoreIssue(
            issueId,
            adminUserId,
            request.reason()
        ));
    }

    @Operation(summary = "Reconciliation Batch 수동 실행")
    @PostMapping(value = "/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SyncReconciliationAdminResponse>> reconcile(
        @CurrentUser Long adminUserId,
        @RequestBody @Valid RunSyncReconciliationRequest request
    ) {
        return ResponseUtils.ok(syncAdminCommandService.reconcile(
            request.cursor(),
            request.mode(),
            adminUserId
        ));
    }
}
