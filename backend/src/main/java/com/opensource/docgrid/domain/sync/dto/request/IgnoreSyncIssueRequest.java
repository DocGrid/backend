package com.opensource.docgrid.domain.sync.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자가 자동 조치하지 않을 정합성 Issue에 남기는 감사 사유다.
 */
public record IgnoreSyncIssueRequest(
    @NotBlank
    @Size(max = 1000)
    String reason
) {
}
