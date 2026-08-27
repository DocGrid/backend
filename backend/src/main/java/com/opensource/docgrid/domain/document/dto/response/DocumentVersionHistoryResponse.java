package com.opensource.docgrid.domain.document.dto.response;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 문서 상세 화면의 버전 타임라인에 필요한 버전·파일·최신 인덱싱 Job Snapshot을 반환한다.
 * 저장소 위치, Claim Token, 오류 상세문처럼 보안 또는 운영 내부 값은 응답 경계 밖에 둔다.
 */
@Schema(description = "문서 버전 이력 항목")
public record DocumentVersionHistoryResponse(
    @Schema(description = "문서 버전 ID") Long documentVersionId,
    @Schema(description = "문서 내부 버전 번호") int versionNo,
    @Schema(description = "버전 처리 상태") DocumentVersionStatus status,
    @Schema(description = "현재 검색에 사용되는 버전 여부") boolean current,
    @Schema(description = "업로드 당시 원본 파일명") String originalFilename,
    @Schema(description = "원본 MIME 타입") String contentType,
    @Schema(description = "원본 파일 크기(Byte)") Long fileSize,
    @Schema(description = "원본 파일 SHA-256") String fileHash,
    @Schema(description = "버전 생성 사용자 ID") Long createdByUserId,
    @Schema(description = "버전 생성 사용자 이름") String createdByName,
    @Schema(description = "버전 생성 시각") LocalDateTime createdAt,
    @Schema(description = "인덱싱 완료 시각") LocalDateTime indexedAt,
    @Schema(description = "이 버전의 최신 인덱싱 Job ID") Long latestJobId,
    @Schema(description = "최신 인덱싱 Job 상태") EmbeddingJobStatus latestJobStatus,
    @Schema(description = "최신 Job을 마지막으로 처리한 Worker 이름") String latestWorkerName,
    @Schema(description = "최신 Job 오류 코드") String errorCode,
    @Schema(description = "현재 재시도 횟수") Integer retryCount,
    @Schema(description = "최대 자동 재시도 횟수") Integer maxRetryCount
) {
}
