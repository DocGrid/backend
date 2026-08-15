package com.opensource.docgrid.domain.search.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.service.RagFacade;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
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
    private final RagFacade ragFacade;

    @Operation(
        summary = "벡터 검색 + RAG 답변 생성",
        description = "질문 텍스트를 임베딩 후 pgvector 코사인 유사도 기준 Top-K 문서 청크를 찾고, "
            + "그 청크를 근거로 LLM이 생성한 답변(answer)과 출처(citations)를 함께 반환합니다. "
            + "topK 기본값은 5이며 1~20 범위에서 지정할 수 있지만, 서버의 최소 유사도 기준을 "
            + "통과한 결과만 반환하므로 실제 결과 수는 topK보다 적을 수 있습니다. "
            + "collectionId를 지정하면 해당 컬렉션 내 문서로 검색 범위를 좁힙니다. "
            + "권한이 없는 문서는 결과에 포함되지 않으며, 접근 가능하고 관련성 있는 문서가 없으면 "
            + "answer에 고정 안내 문구가 반환됩니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestBody @Valid SearchRequest request
    ) {
        SearchOutcome outcome = searchFacade.search(userId, request);
        RagAnswer ragAnswer = ragFacade.generate(
            outcome.response().queryId(), request.queryText(), outcome.candidates(), outcome.savedResults()
        );
        return ResponseUtils.ok(outcome.response().withAnswer(ragAnswer.answerText(), ragAnswer.citations()));
    }
}
