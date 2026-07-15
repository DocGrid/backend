package com.opensource.docgrid.domain.document.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.domain.document.config.DocumentUploadProperties;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileValidationService {

    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
        "txt", Set.of("text/plain"),
        "md", Set.of("text/markdown", "text/plain")
    );

    private final DocumentUploadProperties documentUploadProperties;

    public ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new DocGridException(ErrorCode.EMPTY_FILE);
        }
        if (file.getSize() > documentUploadProperties.getMaxFileSize().toBytes()) {
            throw new DocGridException(ErrorCode.FILE_SIZE_EXCEEDED);
        }

        String originalFilename = normalizeFilename(file.getOriginalFilename());
        String extension = extractExtension(originalFilename);
        String contentType = file.getContentType();

        Set<String> allowedContentTypes = ALLOWED_CONTENT_TYPES.get(extension);
        if (allowedContentTypes == null) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_EXTENSION);
        }
        if (contentType == null || !allowedContentTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        }

        DocumentType documentType = extension.equals("txt") ? DocumentType.TXT : DocumentType.MD;
        return new ValidatedFile(originalFilename, extension, contentType, file.getSize(), documentType);
    }

    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new DocGridException(ErrorCode.INVALID_FILE_NAME);
        }

        String normalized = Normalizer.normalize(filename.trim(), Normalizer.Form.NFC);
        boolean hasControlCharacter = normalized.codePoints().anyMatch(Character::isISOControl);
        if (normalized.isBlank() || hasControlCharacter || normalized.contains("..")
                || normalized.contains("/") || normalized.contains("\\")) {
            throw new DocGridException(ErrorCode.INVALID_FILE_NAME);
        }
        return normalized;
    }

    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_EXTENSION);
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
