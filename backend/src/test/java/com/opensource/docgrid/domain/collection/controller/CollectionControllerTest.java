package com.opensource.docgrid.domain.collection.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.service.command.CollectionCommandService;
import com.opensource.docgrid.domain.collection.service.query.CollectionQueryService;
import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.global.common.response.PageResponse;

/**
 * 컬렉션 문서 목록 API의 인증 사용자 전달, Pagination과 공개 응답 구조를 검증한다.
 */
@WebMvcTest(CollectionController.class)
@DisplayName("CollectionController 테스트")
class CollectionControllerTest {

    private static final String DOCUMENTS_URL = "/collections/{collectionId}/documents";
    private static final String CHILDREN_URL = "/collections/{collectionId}/children";
    private static final String COLLECTIONS_URL = "/collections";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CollectionCommandService collectionCommandService;
    @MockitoBean private CollectionQueryService collectionQueryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("인증된 사용자가 읽기 가능한 컬렉션 문서를 페이지 조회한다")
    void getCollectionDocuments_returnsReadableDocumentPage() throws Exception {
        DocumentSummaryResponse document = new DocumentSummaryResponse(
                5L,
                "운영 가이드",
                "배포 절차",
                DocumentType.PDF,
                DocumentStatus.INDEXED,
                VisibilityType.PRIVATE,
                10L,
                "테스트유저",
                2,
                DocumentVersionStatus.INDEXED,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 2, 10, 0)
        );
        CollectionDocumentListItemResponse item = new CollectionDocumentListItemResponse(
                1L,
                document,
                10L,
                "테스트유저",
                LocalDateTime.of(2026, 8, 3, 10, 0)
        );
        given(collectionQueryService.getCollectionDocuments(10L, 1L, 0, 20))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(DOCUMENTS_URL, 1L)
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].collectionId").value(1))
                .andExpect(jsonPath("$.data.content[0].document.documentId").value(5))
                .andExpect(jsonPath("$.data.content[0].document.title").value("운영 가이드"))
                .andExpect(jsonPath("$.data.content[0].document.currentVersionNo").value(2))
                .andExpect(jsonPath("$.data.content[0].document.ownerName").value("테스트유저"))
                .andExpect(jsonPath("$.data.content[0].addedBy").value(10))
                .andExpect(jsonPath("$.data.content[0].addedByName").value("테스트유저"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("페이지 입력 범위를 벗어나면 400을 반환한다")
    void getCollectionDocuments_returnsBadRequest_whenPageInputIsInvalid() throws Exception {
        mockMvc.perform(get(DOCUMENTS_URL, 1L)
                        .param("size", "101")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("인증된 사용자가 직계 자식 컬렉션 목록을 조회한다")
    void getChildren_returnsChildCollections() throws Exception {
        CollectionResponse child = new CollectionResponse(
                2L, "하위 컬렉션", null, 10L, "테스트유저", 1L, VisibilityType.PRIVATE, CollectionStatus.ACTIVE,
                LocalDateTime.of(2026, 8, 1, 10, 0)
        );
        given(collectionQueryService.getChildren(10L, 1L)).willReturn(List.of(child));

        mockMvc.perform(get(CHILDREN_URL, 1L)
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].collectionId").value(2))
                .andExpect(jsonPath("$.data[0].ownerName").value("테스트유저"))
                .andExpect(jsonPath("$.data[0].parentCollectionId").value(1));
    }

    @Test
    @DisplayName("인증된 사용자가 읽을 수 있는 컬렉션을 페이지 조회한다")
    void getCollections_returnsReadableCollectionPage() throws Exception {
        CollectionResponse collection = new CollectionResponse(
                1L, "인사팀", null, 10L, "테스트유저", null, VisibilityType.PRIVATE, CollectionStatus.ACTIVE,
                LocalDateTime.of(2026, 8, 1, 10, 0)
        );
        given(collectionQueryService.getCollections(10L, null, 0, 20))
                .willReturn(new PageResponse<>(List.of(collection), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(COLLECTIONS_URL)
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].collectionId").value(1))
                .andExpect(jsonPath("$.data.content[0].ownerName").value("테스트유저"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "password",
                List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
