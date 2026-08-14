package com.opensource.docgrid.domain.document.controller;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.document.dto.request.UpdateDocumentMetadataRequest;
import com.opensource.docgrid.domain.document.service.command.DocumentCommandService;

/**
 * 문서 수정·삭제 HTTP 경계의 인증, 검증과 Service 위임을 확인한다.
 */
@WebMvcTest(DocumentCommandController.class)
@DisplayName("DocumentCommandController 테스트")
class DocumentCommandControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DocumentCommandService documentCommandService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증된 사용자가 유효한 제목과 설명을 수정하면 204를 반환한다")
    void updateDocumentMetadata_returnsNoContent_whenRequestIsValid() throws Exception {
        mockMvc.perform(patch("/api/documents/{documentId}", 10L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"변경한 제목","description":"변경한 설명"}
                    """)
                .with(csrf())
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isNoContent());

        then(documentCommandService).should().updateMetadata(
            20L,
            10L,
            new UpdateDocumentMetadataRequest("변경한 제목", "변경한 설명")
        );
    }

    @Test
    @DisplayName("공백 제목으로 문서 정보를 수정하면 400을 반환한다")
    void updateDocumentMetadata_returnsBadRequest_whenTitleIsBlank() throws Exception {
        mockMvc.perform(patch("/api/documents/{documentId}", 10L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"   ","description":"설명"}
                    """)
                .with(csrf())
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON-002"));

        then(documentCommandService).should(never()).updateMetadata(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(UpdateDocumentMetadataRequest.class)
        );
    }

    @Test
    @DisplayName("인증된 사용자가 문서를 삭제하면 204를 반환한다")
    void deleteDocument_returnsNoContent_whenAuthenticated() throws Exception {
        mockMvc.perform(delete("/api/documents/{documentId}", 10L)
                .with(csrf())
                .with(authentication(authenticationWithUserId(20L))))
            .andExpect(status().isNoContent());

        then(documentCommandService).should().deleteDocument(20L, 10L);
    }

    @Test
    @DisplayName("인증되지 않은 문서 삭제 요청은 401을 반환한다")
    void deleteDocument_returnsUnauthorized_whenNotAuthenticated() throws Exception {
        mockMvc.perform(delete("/api/documents/{documentId}", 10L).with(csrf()))
            .andExpect(status().isUnauthorized());

        then(documentCommandService).shouldHaveNoInteractions();
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user", "password", List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
