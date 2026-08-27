package com.opensource.docgrid.domain.document.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionHistoryResponse;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;

/**
 * 문서 버전과 최신 인덱싱 Job이 외부 버전 이력 응답으로 결합되는 경계를 검증한다.
 */
@DisplayName("DocumentVersionHistoryConverter 단위 테스트")
class DocumentVersionHistoryConverterTest {

    private final DocumentVersionHistoryConverter converter = new DocumentVersionHistoryConverter();

    @Test
    @DisplayName("버전 Snapshot과 최신 Job 상태를 현재 버전 표시와 함께 반환한다")
    void toResponse_combinesVersionAndLatestJob() {
        DocumentVersion version = mock(DocumentVersion.class);
        FileObject fileObject = mock(FileObject.class);
        User createdBy = mock(User.class);
        EmbeddingJob job = mock(EmbeddingJob.class);
        WorkerNode worker = mock(WorkerNode.class);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 27, 10, 0);

        given(version.getId()).willReturn(31L);
        given(version.getVersionNo()).willReturn(2);
        given(version.getStatus()).willReturn(DocumentVersionStatus.FAILED);
        given(version.getFileObject()).willReturn(fileObject);
        given(version.getOriginalFilename()).willReturn("security-v2.docx");
        given(version.getContentType()).willReturn("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        given(version.getFileHash()).willReturn("version-hash");
        given(version.getCreatedBy()).willReturn(createdBy);
        given(version.getCreatedAt()).willReturn(createdAt);
        given(fileObject.getFileSize()).willReturn(2048L);
        given(createdBy.getId()).willReturn(20L);
        given(createdBy.getName()).willReturn("관리자");
        given(job.getId()).willReturn(41L);
        given(job.getStatus()).willReturn(EmbeddingJobStatus.FAILED);
        given(job.getLockedByWorker()).willReturn(worker);
        given(job.getErrorCode()).willReturn("EMBEDDING_PROVIDER_TIMEOUT");
        given(job.getRetryCount()).willReturn(3);
        given(job.getMaxRetryCount()).willReturn(3);
        given(worker.getWorkerName()).willReturn("worker-b");

        DocumentVersionHistoryResponse response = converter.toResponse(version, 31L, job);

        assertThat(response.documentVersionId()).isEqualTo(31L);
        assertThat(response.current()).isTrue();
        assertThat(response.originalFilename()).isEqualTo("security-v2.docx");
        assertThat(response.fileSize()).isEqualTo(2048L);
        assertThat(response.createdByUserId()).isEqualTo(20L);
        assertThat(response.latestJobId()).isEqualTo(41L);
        assertThat(response.latestJobStatus()).isEqualTo(EmbeddingJobStatus.FAILED);
        assertThat(response.latestWorkerName()).isEqualTo("worker-b");
        assertThat(response.errorCode()).isEqualTo("EMBEDDING_PROVIDER_TIMEOUT");
    }
}
