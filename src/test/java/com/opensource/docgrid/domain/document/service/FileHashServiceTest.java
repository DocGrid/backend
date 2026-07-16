package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileHashService 단위 테스트")
class FileHashServiceTest {

    private final FileHashService fileHashService = new FileHashService();

    @Mock
    private MultipartFile multipartFile;

    @Test
    @DisplayName("파일 내용을 SHA-256 소문자 hex로 변환한다")
    void calculateSha256_returnsKnownHash() {
        MockMultipartFile file = new MockMultipartFile("file", "sample.txt", "text/plain", "hello".getBytes());

        String result = fileHashService.calculateSha256(file);

        assertThat(result).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("파일 스트림을 읽지 못하면 전용 예외가 발생한다")
    void calculateSha256_throws_when_streamFails() throws IOException {
        given(multipartFile.getInputStream()).willThrow(new IOException("stream failure"));

        assertThatThrownBy(() -> fileHashService.calculateSha256(multipartFile))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_HASH_CALCULATION_FAILED);
    }
}
