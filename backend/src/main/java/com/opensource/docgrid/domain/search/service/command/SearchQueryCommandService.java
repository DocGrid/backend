package com.opensource.docgrid.domain.search.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.search.dto.SearchAdmission;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchQueryCommandService {

    private final SearchQueryRepository searchQueryRepository;
    private final SearchConversationCommandService searchConversationCommandService;
    private final UserRepository userRepository;
    private final CollectionRepository collectionRepository;

    /**
     * 사용자·컬렉션을 검증하고 대화방 변경과 PROCESSING 검색 원장을 한 Transaction으로 커밋한다.
     *
     * <p>searchType은 파라미터로 받지 않고 VECTOR로 고정한다 — 현재는 벡터 검색만 지원하며,
     * KEYWORD/HYBRID는 추후 확장 시 이 부분부터 파라미터화가 필요하다. 임베딩 모델과 Vector는
     * 외부 호출 성공 뒤 결과 저장 Transaction에서 채운다.
     */
    public SearchAdmission createProcessing(
        Long userId,
        Long conversationId,
        Long collectionId,
        String queryText,
        int topK
    ) {
        // 1. 원장 FK를 먼저 검증해 잘못된 요청이 대화방을 생성하거나 갱신하지 않게 한다.
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        DocumentCollection collection = resolveCollection(collectionId);

        // 2. 대화방 생성·활동 시각 갱신과 검색 원장 INSERT를 같은 Transaction에 둔다.
        SearchConversation conversation = searchConversationCommandService.resolve(user, conversationId, queryText);
        SearchQuery searchQuery = SearchQuery.builder()
            .user(user)
            .conversation(conversation)
            .collection(collection)
            .queryText(queryText)
            .searchType(SearchType.VECTOR)
            .topK(topK)
            .status(ResultStatus.PROCESSING)
            .build();
        SearchQuery saved = searchQueryRepository.save(searchQuery);
        return new SearchAdmission(conversation.getId(), saved.getId());
    }

    /**
     * 검색 도중 실패했을 때 상태를 FAILED로 기록한다.
     *
     * <p>외부 호출 전에 별도 Transaction으로 커밋된 원장을 {@code queryId} 조건부 UPDATE로
     * 변경한다. 다른 영속성 Context가 관리하던 엔티티를 전달하지 않으며, 이미 SUCCESS/FAILED로
     * 끝난 검색이면 아무 상태도 덮어쓰지 않는다.
     *
     * @return PROCESSING 원장을 실제로 FAILED로 바꿨으면 true, 이미 끝났거나 없으면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long queryId, String errorMessage) {
        boolean updated = searchQueryRepository.markFailedIfProcessing(queryId, errorMessage) > 0;
        if (!updated) {
            log.warn("[SEARCH] FAILED 기록 생략 queryId={} reason=not-processing-or-missing", queryId);
        }
        return updated;
    }

    /** 컬렉션 범위를 지정한 요청만 같은 접수 Transaction에서 FK 존재 여부를 확인한다. */
    private DocumentCollection resolveCollection(Long collectionId) {
        if (collectionId == null) {
            return null;
        }
        return collectionRepository.findById(collectionId)
            .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    }
}
