package com.opensource.docgrid.domain.collection.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.response.CollectionDocumentListItemResponse;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRow;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 컬렉션 단건과 읽기 가능한 컬렉션 문서 페이지의 권한·Pagination 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionQueryService 단위 테스트")
class CollectionQueryServiceTest {

    @InjectMocks
    private CollectionQueryService collectionQueryService;

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private CollectionDocumentRepository collectionDocumentRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private CollectionConverter collectionConverter;

    @Mock
    private PermissionQueryService permissionQueryService;

    @Test
    @DisplayName("읽기 권한이 있는 사용자가 조회하면 CollectionResponse를 반환한다")
    void getCollection_returnsResponse_when_collectionExists() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(collectionConverter.toResponse(collection)).willReturn(expected);

        CollectionResponse result = collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isEqualTo(expected);
        then(collectionConverter).should().toResponse(collection);
    }

    @Test
    @DisplayName("존재하지 않는 컬렉션 ID로 조회하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void getCollection_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 컬렉션을 조회하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void getCollection_throws_when_collectionDeleted() {
        DocumentCollection collection = CollectionFixture.createCollection();
        org.springframework.test.util.ReflectionTestUtils.setField(collection, "status", CollectionStatus.DELETED);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> collectionQueryService.getCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("읽기 권한이 없는 사용자가 조회하면 PERMISSION_DENIED 예외가 발생한다")
    void getCollection_throws_when_noReadPermission() {
        DocumentCollection collection = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(otherUserId, collection)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("읽을 수 있는 컬렉션이 없으면 빈 페이지를 반환한다")
    void getCollections_returnsEmptyPage_whenNoReadableCollection() {
        given(collectionRepository.findReadableCollections(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(0L)))
                .willReturn(List.of());
        given(collectionRepository.countReadableCollections(CollectionFixture.USER_ID, null))
                .willReturn(0L);

        PageResponse<CollectionResponse> result = collectionQueryService.getCollections(CollectionFixture.USER_ID, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("요청한 페이지가 마지막 페이지를 넘어가 0건이 반환돼도, 별도 count 쿼리로 실제 전체 개수를 정확히 반영한다")
    void getCollections_returnsAccurateTotalElements_whenPageBeyondLastPage() {
        // COUNT(*) OVER()는 반환된 행에만 얹혀 계산되므로, offset이 범위를 넘어 0건이 반환되면
        // findReadableCollections만으로는 전체 개수(실제로는 3건)를 전혀 알 수 없다 — 이걸 그대로
        // totalElements=0으로 응답하면 "정말 0건"과 "빈 페이지"를 구분 못 하는 버그가 된다.
        given(collectionRepository.findReadableCollections(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(100L)))
                .willReturn(List.of());
        given(collectionRepository.countReadableCollections(CollectionFixture.USER_ID, null))
                .willReturn(3L);

        PageResponse<CollectionResponse> result = collectionQueryService.getCollections(CollectionFixture.USER_ID, null, 5, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("읽을 수 있는 컬렉션을 페이지로 조회해서 응답으로 변환하고, 첫 행의 totalCount를 전체 개수로 쓴다")
    void getCollections_returnsPagedResponses() {
        // size=1로 첫 페이지만 요청 — totalCount(3)이 이번 페이지 content 크기(1)보다 크다는 걸
        // PageImpl이 "모순"으로 보정하지 않도록, 실제로 더 남은 페이지가 있는 상황으로 맞춘다.
        CollectionRow row = mockCollectionRow(3L);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findReadableCollections(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(0L)))
                .willReturn(List.of(row));
        given(collectionConverter.toResponse(row)).willReturn(expected);

        PageResponse<CollectionResponse> result = collectionQueryService.getCollections(CollectionFixture.USER_ID, null, 0, 1);

        assertThat(result.content()).containsExactly(expected);
        assertThat(result.totalElements()).isEqualTo(3);
        // 정상 경로(행이 반환됨)에서는 별도 count 쿼리를 부르지 않는다 — 이게 이 설계의 핵심
        // 최적화(콘텐츠+count를 한 쿼리로 합침)이므로, 불필요하게 두 번째 쿼리가 나가지 않는지도 검증한다.
        then(collectionRepository).should(never()).countReadableCollections(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("keyword와 page/size로 계산한 limit/offset을 그대로 repository에 전달한다")
    void getCollections_passesKeywordAndOffsetToRepository() {
        given(collectionRepository.findReadableCollections(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq("개발"),
                org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(20L)))
                .willReturn(List.of());

        collectionQueryService.getCollections(CollectionFixture.USER_ID, "개발", 1, 20);

        then(collectionRepository).should().findReadableCollections(CollectionFixture.USER_ID, "개발", 20, 20L);
    }

    // collectionConverter.toResponse(row)를 목으로 대체하므로, 서비스가 직접 읽는
    // getTotalCount()만 스텁하면 충분하다 (다른 getter는 이 단위 테스트에서 호출되지 않음).
    private CollectionRow mockCollectionRow(long totalCount) {
        CollectionRow row = org.mockito.Mockito.mock(CollectionRow.class);
        given(row.getTotalCount()).willReturn(totalCount);
        return row;
    }

    @Test
    @DisplayName("부모 읽기 권한이 있으면 findReadableChildren이 반환한 자식 목록을 응답으로 변환하고, "
            + "자식마다 canReadCollection을 다시 호출하지 않는다(N+1 제거 검증)")
    void getChildren_returnsResponses_when_parentIsReadable() {
        DocumentCollection parent = CollectionFixture.createCollection();
        // 자식을 3개 반환하도록 스텁 — 만약 서비스가 예전처럼 자식마다 canReadCollection을 다시
        // 호출한다면 아래 verify(times(1))가 실패해서 잡아낸다 (자식 1개짜리로는 이 회귀를 못 잡음).
        DocumentCollection child1 = CollectionFixture.createChildCollection(parent.getOwner(), parent, 2L);
        DocumentCollection child2 = CollectionFixture.createChildCollection(parent.getOwner(), parent, 3L);
        DocumentCollection child3 = CollectionFixture.createChildCollection(parent.getOwner(), parent, 4L);
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, parent)).willReturn(true);
        given(collectionRepository.findReadableChildren(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID))
                .willReturn(List.of(child1, child2, child3));
        given(collectionConverter.toResponse(org.mockito.ArgumentMatchers.any(DocumentCollection.class))).willReturn(expected);

        List<CollectionResponse> result = collectionQueryService.getChildren(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).hasSize(3);
        then(permissionQueryService).should(org.mockito.Mockito.times(1))
                .canReadCollection(org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID), org.mockito.ArgumentMatchers.any(DocumentCollection.class));
    }

    @Test
    @DisplayName("부모 읽기 권한이 없으면 PERMISSION_DENIED 예외가 발생한다")
    void getChildren_throws_when_parentReadIsDenied() {
        DocumentCollection parent = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(parent));
        given(permissionQueryService.canReadCollection(otherUserId, parent)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getChildren(otherUserId, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(collectionRepository).should(never()).findReadableChildren(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("컬렉션 문서 목록은 읽기 가능한 문서만 최신 추가순으로 페이지 반환한다")
    void getCollectionDocuments_returnsOnlyReadableDocuments() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionDocument collectionDocument = org.mockito.Mockito.mock(CollectionDocument.class);
        CollectionDocumentListItemResponse expected = org.mockito.Mockito.mock(
                CollectionDocumentListItemResponse.class
        );
        List<Long> readableDocumentIds = List.of(CollectionFixture.DOCUMENT_ID);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.anyList()
        )).willReturn(readableDocumentIds);
        given(collectionDocumentRepository.findReadableDocuments(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.eq(readableDocumentIds),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(collectionDocument), PageRequest.of(0, 20), 1));
        given(collectionConverter.toDocumentListItemResponse(collectionDocument)).willReturn(expected);

        PageResponse<CollectionDocumentListItemResponse> result = collectionQueryService.getCollectionDocuments(
                CollectionFixture.USER_ID,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        );

        assertThat(result.content()).containsExactly(expected);
        assertThat(result.totalElements()).isEqualTo(1);
        then(documentRepository).should().findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.argThat(statuses -> !statuses.contains("DELETED"))
        );
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(collectionDocumentRepository).should().findReadableDocuments(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.eq(readableDocumentIds),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("addedAt").isDescending()).isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("읽기 가능한 문서가 없으면 숨김 문서 수를 노출하지 않는 빈 페이지를 반환한다")
    void getCollectionDocuments_returnsEmptyPage_whenNoDocumentIsReadable() {
        DocumentCollection collection = CollectionFixture.createCollection();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(documentRepository.findReadableDocumentIdsInCollection(
                org.mockito.ArgumentMatchers.eq(CollectionFixture.USER_ID),
                org.mockito.ArgumentMatchers.eq(CollectionFixture.COLLECTION_ID),
                org.mockito.ArgumentMatchers.anyList()
        )).willReturn(List.of());

        PageResponse<CollectionDocumentListItemResponse> result = collectionQueryService.getCollectionDocuments(
                CollectionFixture.USER_ID,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        );

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        then(collectionDocumentRepository).should(never()).findReadableDocuments(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        );
    }

    @Test
    @DisplayName("컬렉션 읽기 권한이 없으면 문서 ID 조회 전에 거부한다")
    void getCollectionDocuments_throwsBeforeDocumentQuery_whenCollectionReadIsDenied() {
        DocumentCollection collection = CollectionFixture.createCollection();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canReadCollection(otherUserId, collection)).willReturn(false);

        assertThatThrownBy(() -> collectionQueryService.getCollectionDocuments(
                otherUserId,
                CollectionFixture.COLLECTION_ID,
                0,
                20
        ))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentRepository).shouldHaveNoInteractions();
    }
}
