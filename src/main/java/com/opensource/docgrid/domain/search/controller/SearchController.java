package com.opensource.docgrid.domain.search.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Search", description = "벡터 검색 API")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchFacade searchFacade;

    @Operation(
        summary = "벡터 검색",
        description = "질문 텍스트를 임베딩 후 pgvector 코사인 유사도 기준 Top-K 문서 청크를 반환합니다. "
            + "topK 기본값은 5이며 1~20 범위에서 지정할 수 있습니다. "
            + "collectionId를 지정하면 해당 컬렉션 내 문서로 검색 범위를 좁힙니다. "
            + "권한이 없는 문서는 결과에 포함되지 않으며, 접근 가능한 문서가 없으면 빈 배열을 반환합니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestBody @Valid SearchRequest request
    ) {
        return ResponseUtils.ok(searchFacade.search(userId, request));
    }
}
