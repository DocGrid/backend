package com.opensource.docgrid.domain.document.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.document.dto.response.CurrentVersionStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentContentResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.ProcessingVersionStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentFileDownload;
import com.opensource.docgrid.domain.document.service.DocumentFileService;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@WebMvcTest(DocumentQueryController.class)
@DisplayName("DocumentQueryController 테스트")
class DocumentQueryControllerTest {

    private static final String STATUS_URL = "/api/documents/{documentId}/status";
    private static final String DETAIL_URL = "/api/documents/{documentId}";
    private static final String CONTENT_URL = "/api/documents/{documentId}/content";
    private static final String FILE_URL = "/api/documents/{documentId}/file";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentQueryService documentQueryService;

    @MockitoBean
    private DocumentFileService documentFileService;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증된 사용자가 본문과 파일을 제외한 문서 상세 정보를 조회한다")
    void getDocumentDetail_returnsDocumentMetadata() throws Exception {
        DocumentDetailResponse response = new DocumentDetailResponse(
            10L,
            "운영 가이드",
            "배포 절차",
            DocumentType.PDF,
            DocumentSourceType.UPLOAD,
            DocumentStatus.INDEXED,
            VisibilityType.PRIVATE,
            20L,
            "소유자",
            null,
            true,
            LocalDateTime.of(2026, 8, 1, 10, 0),
            LocalDateTime.of(2026, 8, 2, 11, 0)
        );
        given(documentQueryService.getDocumentDetail(20L, 10L)).willReturn(response);

        mockMvc.perform(get(DETAIL_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentId").value(10))
            .andExpect(jsonPath("$.data.title").value("운영 가이드"))
            .andExpect(jsonPath("$.data.ownerName").value("소유자"))
            .andExpect(jsonPath("$.data.contentAvailable").value(true))
            .andExpect(jsonPath("$.data.currentVersion").doesNotExist());
    }

    @Test
    @DisplayName("인증된 사용자가 현재 버전에서 추출한 전체 텍스트를 조회한다")
    void getDocumentContent_returnsRestoredText() throws Exception {
        DocumentContentResponse response = new DocumentContentResponse(
            10L, 30L, 2, "첫 페이지\n둘째 페이지", 4
        );
        given(documentQueryService.getDocumentContent(20L, 10L)).willReturn(response);

        mockMvc.perform(get(CONTENT_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.documentVersionId").value(30))
            .andExpect(jsonPath("$.data.content").value("첫 페이지\n둘째 페이지"))
            .andExpect(jsonPath("$.data.chunkCount").value(4));
    }

    @Test
    @DisplayName("원본 파일 조회 기본값은 브라우저 표시용 inline 응답이다")
    void getDocumentFile_returnsInlineFileByDefault() throws Exception {
        byte[] fileContent = "%PDF-test".getBytes(StandardCharsets.UTF_8);
        given(documentFileService.getDocumentFile(20L, 10L)).willReturn(
            new DocumentFileDownload(fileContent, "운영 가이드.pdf", "application/pdf", fileContent.length)
        );

        mockMvc.perform(get(FILE_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")))
            .andExpect(content().bytes(fileContent));
    }

    @Test
    @DisplayName("attachment를 지정하면 원본 파일을 다운로드 응답으로 반환한다")
    void getDocumentFile_returnsAttachment_whenRequested() throws Exception {
        byte[] fileContent = "text".getBytes(StandardCharsets.UTF_8);
        given(documentFileService.getDocumentFile(20L, 10L)).willReturn(
            new DocumentFileDownload(fileContent, "guide.txt", "text/plain", fileContent.length)
        );

        mockMvc.perform(get(FILE_URL, 10L)
                .param("disposition", "attachment")
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
    }

    @Test
    @DisplayName("지원하지 않는 disposition이면 400을 반환한다")
    void getDocumentFile_returnsBadRequest_whenDispositionIsInvalid() throws Exception {
        mockMvc.perform(get(FILE_URL, 10L)
                .param("disposition", "preview")
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("인증된 사용자가 현재 버전과 처리 중 버전 상태를 조회한다")
    void getDocumentStatus_returnsCurrentAndProcessingVersions() throws Exception {
        DocumentStatusResponse response = new DocumentStatusResponse(
            10L,
            DocumentStatus.INDEXED,
            new CurrentVersionStatusResponse(1, DocumentVersionStatus.INDEXED),
            new ProcessingVersionStatusResponse(
                2, DocumentVersionStatus.PARSING, EmbeddingJobStatus.PROCESSING
            )
        );
        given(documentQueryService.getDocumentStatus(20L, 10L)).willReturn(response);

        mockMvc.perform(get(STATUS_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.documentId").value(10))
            .andExpect(jsonPath("$.data.documentStatus").value("INDEXED"))
            .andExpect(jsonPath("$.data.currentVersion.versionNo").value(1))
            .andExpect(jsonPath("$.data.currentVersion.status").value("INDEXED"))
            .andExpect(jsonPath("$.data.processingVersion.versionNo").value(2))
            .andExpect(jsonPath("$.data.processingVersion.status").value("PARSING"))
            .andExpect(jsonPath("$.data.processingVersion.jobStatus").value("PROCESSING"));
    }

    @Test
    @DisplayName("최초 버전 처리 중에는 currentVersion을 null로 반환한다")
    void getDocumentStatus_returnsNullCurrentVersion_when_initialVersionIsProcessing() throws Exception {
        DocumentStatusResponse response = new DocumentStatusResponse(
            10L,
            DocumentStatus.UPLOADED,
            null,
            new ProcessingVersionStatusResponse(
                1, DocumentVersionStatus.UPLOADED, EmbeddingJobStatus.PENDING
            )
        );
        given(documentQueryService.getDocumentStatus(20L, 10L)).willReturn(response);

        mockMvc.perform(get(STATUS_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currentVersion").doesNotExist())
            .andExpect(jsonPath("$.data.processingVersion.versionNo").value(1))
            .andExpect(jsonPath("$.data.processingVersion.jobStatus").value("PENDING"));
    }

    @Test
    @DisplayName("읽기 권한이 없으면 403을 반환한다")
    void getDocumentStatus_returnsForbidden_when_readPermissionIsDenied() throws Exception {
        given(documentQueryService.getDocumentStatus(20L, 10L))
            .willThrow(new DocGridException(ErrorCode.PERMISSION_DENIED));

        mockMvc.perform(get(STATUS_URL, 10L)
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ROLE-002"));
    }

    @Test
    @DisplayName("인증되지 않은 사용자는 401을 반환한다")
    void getDocumentStatus_returnsUnauthorized_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(get(STATUS_URL, 10L))
            .andExpect(status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user", "password", List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
