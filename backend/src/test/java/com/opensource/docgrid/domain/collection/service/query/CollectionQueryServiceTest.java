package com.opensource.docgrid.domain.collection.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.collection.converter.CollectionConverter;
import com.opensource.docgrid.domain.collection.dto.response.CollectionResponse;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionQueryService 단위 테스트")
class CollectionQueryServiceTest {

    @InjectMocks
    private CollectionQueryService collectionQueryService;

    @Mock
    private CollectionRepository collectionRepository;

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
}
