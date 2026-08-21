package com.opensource.docgrid.domain.document.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.FileStorageType;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 파일 중복 재사용이 현재 Adapter의 Provider·Bucket 경계를 넘지 않는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileObjectResolutionService 테스트")
class FileObjectResolutionServiceTest {

    private static final String FILE_HASH = "same-hash";
    private static final long FILE_SIZE = 4L;

    @Mock private FileObjectRepository fileObjectRepository;

    private FileObjectResolutionService service;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setType(FileStorageType.MINIO);
        properties.setBucket("active-bucket");
        service = new FileObjectResolutionService(fileObjectRepository, properties);
    }

    @Test
    @DisplayName("현재 Provider와 Bucket의 동일 파일은 재사용한다")
    void findReusableFileObjectId_returnsId_whenLocationMatches() {
        FileObject fileObject = fileObject(StorageProvider.MINIO, "active-bucket", 3L);
        given(fileObjectRepository.findByFileHashAndFileSize(FILE_HASH, FILE_SIZE))
            .willReturn(Optional.of(fileObject));

        assertThat(service.findReusableFileObjectId(FILE_HASH, FILE_SIZE)).contains(3L);
    }

    @Test
    @DisplayName("동일 해시라도 Provider가 다르면 재사용을 거부한다")
    void findReusableFileObjectId_rejectsDifferentProvider() {
        given(fileObjectRepository.findByFileHashAndFileSize(FILE_HASH, FILE_SIZE))
            .willReturn(Optional.of(fileObject(StorageProvider.LOCAL, "active-bucket", 3L)));

        assertResolutionFailure(() -> service.findReusableFileObjectId(FILE_HASH, FILE_SIZE));
    }

    @Test
    @DisplayName("동일 해시라도 Bucket이 다르면 재사용을 거부한다")
    void findReusableFileObjectId_rejectsDifferentBucket() {
        given(fileObjectRepository.findByFileHashAndFileSize(FILE_HASH, FILE_SIZE))
            .willReturn(Optional.of(fileObject(StorageProvider.MINIO, "other-bucket", 3L)));

        assertResolutionFailure(() -> service.findReusableFileObjectId(FILE_HASH, FILE_SIZE));
    }

    @Test
    @DisplayName("동시 Insert가 다른 저장 위치의 기존 Row와 충돌하면 후보를 연결하지 않는다")
    void resolve_rejectsConflictingLocation_afterInsertRace() {
        given(fileObjectRepository.findByFileHashAndFileSize(FILE_HASH, FILE_SIZE))
            .willReturn(Optional.of(fileObject(StorageProvider.LOCAL, "other-bucket", 3L)));
        ValidatedFile validatedFile = new ValidatedFile(
            "sample.txt", "txt", "text/plain", FILE_SIZE, DocumentType.TXT
        );
        StoredFile candidate = new StoredFile(
            StorageProvider.MINIO, "active-bucket", "documents/candidate.txt"
        );

        assertResolutionFailure(() -> service.resolve(
            1L, validatedFile, FILE_HASH, null, candidate
        ));
    }

    private FileObject fileObject(StorageProvider provider, String bucket, Long id) {
        FileObject fileObject = FileObject.builder()
            .bucketName(bucket)
            .objectKey("documents/original.txt")
            .originalFilename("sample.txt")
            .contentType("text/plain")
            .fileSize(FILE_SIZE)
            .fileHash(FILE_HASH)
            .storageProvider(provider)
            .build();
        ReflectionTestUtils.setField(fileObject, "id", id);
        return fileObject;
    }

    private void assertResolutionFailure(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(DocGridException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_OBJECT_RESOLUTION_FAILED)
            );
    }
}
