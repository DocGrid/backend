package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import com.opensource.docgrid.domain.document.config.DocumentUploadProperties;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@DisplayName("FileValidationService 단위 테스트")
class FileValidationServiceTest {

    private FileValidationService fileValidationService;

    @BeforeEach
    void setUp() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(10));
        fileValidationService = new FileValidationService(properties);
    }

    @Test
    @DisplayName("정상 TXT 파일을 검증한다")
    void validate_succeeds_forTextFile() {
        MockMultipartFile file = file("sample.TXT", "text/plain", "hello");

        ValidatedFile result = fileValidationService.validate(file);

        assertThat(result.originalFilename()).isEqualTo("sample.TXT");
        assertThat(result.extension()).isEqualTo("txt");
        assertThat(result.documentType()).isEqualTo(DocumentType.TXT);
    }

    @Test
    @DisplayName("text/plain Markdown 파일을 허용한다")
    void validate_succeeds_forMarkdownWithPlainContentType() {
        ValidatedFile result = fileValidationService.validate(file("README.md", "text/plain", "# title"));

        assertThat(result.documentType()).isEqualTo(DocumentType.MD);
    }

    @Test
    @DisplayName("빈 파일이면 예외가 발생한다")
    void validate_throws_when_fileIsEmpty() {
        assertError(file("empty.txt", "text/plain", ""), ErrorCode.EMPTY_FILE);
    }

    @Test
    @DisplayName("최대 파일 크기를 초과하면 예외가 발생한다")
    void validate_throws_when_fileIsTooLarge() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        properties.setMaxFileSize(DataSize.ofBytes(3));
        fileValidationService = new FileValidationService(properties);

        assertError(file("large.txt", "text/plain", "four"), ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("지원하지 않는 확장자면 예외가 발생한다")
    void validate_throws_when_extensionIsUnsupported() {
        assertError(file("sample.pdf", "application/pdf", "pdf"), ErrorCode.UNSUPPORTED_FILE_EXTENSION);
    }

    @Test
    @DisplayName("확장자와 Content-Type이 일치하지 않으면 예외가 발생한다")
    void validate_throws_when_contentTypeIsUnsupported() {
        assertError(file("sample.md", "application/octet-stream", "md"),
            ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
    }

    @Test
    @DisplayName("경로 문자가 포함된 파일명이면 예외가 발생한다")
    void validate_throws_when_filenameContainsPath() {
        assertError(file("../sample.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
        assertError(file("dir\\sample.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
    }

    @Test
    @DisplayName("제어 문자가 포함된 파일명이면 예외가 발생한다")
    void validate_throws_when_filenameContainsControlCharacter() {
        assertError(file("sample\u0000.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
    }

    private MockMultipartFile file(String filename, String contentType, String content) {
        return new MockMultipartFile("file", filename, contentType, content.getBytes());
    }

    private void assertError(MockMultipartFile file, ErrorCode errorCode) {
        assertThatThrownBy(() -> fileValidationService.validate(file))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", errorCode);
    }
}
