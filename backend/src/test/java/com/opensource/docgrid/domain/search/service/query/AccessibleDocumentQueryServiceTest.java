package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.document.repository.DocumentRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccessibleDocumentQueryService 단위 테스트")
class AccessibleDocumentQueryServiceTest {

    @InjectMocks
    private AccessibleDocumentQueryService accessibleDocumentQueryService;

    @Mock
    private DocumentRepository documentRepository;

    private static final Long USER_ID = 1L;
    private static final Long COLLECTION_ID = 10L;
    private static final List<String> INDEXED_ONLY = List.of("INDEXED");

    @Test
    @DisplayName("collectionId가 null이면 전체 범위 쿼리를 호출하고 결과를 반환한다")
    void findReadableDocumentIds_withoutCollection_callsGlobalQuery() {
        List<Long> expected = List.of(1L, 2L, 3L);
        given(documentRepository.findReadableDocumentIds(USER_ID, INDEXED_ONLY)).willReturn(expected);

        List<Long> result = accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null);

        assertThat(result).isEqualTo(expected);
        then(documentRepository).should(times(1)).findReadableDocumentIds(USER_ID, INDEXED_ONLY);
        then(documentRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("collectionId가 있으면 컬렉션 범위 쿼리를 호출하고 결과를 반환한다")
    void findReadableDocumentIds_withCollection_callsCollectionQuery() {
        List<Long> expected = List.of(2L, 3L);
        given(documentRepository.findReadableDocumentIdsInCollection(USER_ID, COLLECTION_ID, INDEXED_ONLY)).willReturn(expected);

        List<Long> result = accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, COLLECTION_ID);

        assertThat(result).isEqualTo(expected);
        then(documentRepository).should(times(1)).findReadableDocumentIdsInCollection(USER_ID, COLLECTION_ID, INDEXED_ONLY);
        then(documentRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("접근 가능한 문서가 없으면 빈 목록을 반환한다")
    void findReadableDocumentIds_noAccessible_returnsEmptyList() {
        given(documentRepository.findReadableDocumentIds(USER_ID, INDEXED_ONLY)).willReturn(List.of());

        List<Long> result = accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("컬렉션 범위에서 접근 가능한 문서가 없으면 빈 목록을 반환한다")
    void findReadableDocumentIds_noAccessibleInCollection_returnsEmptyList() {
        given(documentRepository.findReadableDocumentIdsInCollection(USER_ID, COLLECTION_ID, INDEXED_ONLY)).willReturn(List.of());

        List<Long> result = accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, COLLECTION_ID);

        assertThat(result).isEmpty();
    }
}
