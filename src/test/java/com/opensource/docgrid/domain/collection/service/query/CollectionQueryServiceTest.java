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
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
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

    @Test
    @DisplayName("존재하는 컬렉션 ID로 조회하면 CollectionResponse를 반환한다")
    void getCollection_returnsResponse_when_collectionExists() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionResponse expected = CollectionFixture.createCollectionResponse();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(collectionConverter.toResponse(collection)).willReturn(expected);

        CollectionResponse result = collectionQueryService.getCollection(CollectionFixture.COLLECTION_ID);

        assertThat(result).isEqualTo(expected);
        then(collectionConverter).should().toResponse(collection);
    }

    @Test
    @DisplayName("존재하지 않는 컬렉션 ID로 조회하면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void getCollection_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> collectionQueryService.getCollection(CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }
}
