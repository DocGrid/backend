package com.opensource.docgrid.domain.document.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.ProcessingVersionStatusResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@WebMvcTest(DocumentQueryController.class)
@DisplayName("DocumentQueryController 테스트")
class DocumentQueryControllerTest {

    private static final String STATUS_URL = "/api/documents/{documentId}/status";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentQueryService documentQueryService;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

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
