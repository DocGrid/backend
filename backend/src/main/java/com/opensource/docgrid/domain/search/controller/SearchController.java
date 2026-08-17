package com.opensource.docgrid.domain.search.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome;
import com.opensource.docgrid.domain.rag.service.RagFacade;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.domain.search.service.query.SearchAnswerQueryService;
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
    private final SearchAnswerQueryService searchAnswerQueryService;

    @Operation(
        summary = "벡터 검색 + RAG 답변 생성 요청",
        description = "질문 텍스트를 임베딩 후 pgvector 코사인 유사도 기준 Top-K 문서 청크를 찾아 즉시 "
            + "반환합니다. AI 답변(answer)은 비동기로 생성되며, 응답 시점에는 ragStatus가 PROCESSING이고 "
            + "answer는 null입니다 — 완성되면 WebSocket(/user/queue/rag-answer)으로 알림이 오며, 그 신호를 "
            + "받으면 GET /search/{queryId}로 최신 상태를 다시 조회하세요. 검색 결과가 없으면(NO_CONTEXT) "
            + "answer가 고정 안내 문구와 함께 즉시(ragStatus=SUCCESS) 반환됩니다. "
            + "topK 기본값은 5이며 1~20 범위에서 지정할 수 있지만, 서버의 최소 유사도 기준을 "
            + "통과하고 문서별 청크 상한을 적용한 결과만 반환하므로 실제 결과 수는 topK보다 적을 수 있습니다. "
            + "collectionId를 지정하면 해당 컬렉션 내 문서로 검색 범위를 좁힙니다. "
            + "권한이 없는 문서는 결과에 포함되지 않습니다."
    )
    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestBody @Valid SearchRequest request
    ) {
        SearchOutcome outcome = searchFacade.search(userId, request);
        RagEnqueueOutcome ragOutcome = ragFacade.enqueue(
            outcome.response().queryId(), request.queryText(), outcome.candidates()
        );
        if (ragOutcome.pending()) {
            return ResponseUtils.ok(outcome.response());
        }
        return ResponseUtils.ok(outcome.response().withAnswer(
            ResultStatus.SUCCESS, ragOutcome.immediateAnswer().answerText(), ragOutcome.immediateAnswer().citations()
        ));
    }

    @Operation(
        summary = "RAG 답변 상태 재조회",
        description = "POST /search 응답의 ragStatus가 PROCESSING이었거나, WebSocket(/user/queue/rag-answer) "
            + "알림을 받은 뒤 최신 상태를 확인할 때 호출합니다. 본인이 요청한 검색만 조회할 수 있습니다."
    )
    @GetMapping("/{queryId}")
    public ResponseEntity<ApiResponse<SearchResponse>> getAnswer(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @PathVariable Long queryId
    ) {
        return ResponseUtils.ok(searchAnswerQueryService.getAnswer(queryId, userId));
    }
}
