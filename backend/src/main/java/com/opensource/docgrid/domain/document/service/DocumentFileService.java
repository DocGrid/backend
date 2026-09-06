package com.opensource.docgrid.domain.document.service;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.service.query.DocumentFileSnapshot;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 문서 원본 파일 조회 Snapshot과 Object Storage 읽기를 Transaction 밖에서 조정한다.
 * 권한 및 현재 버전 선택은 Query Service에 위임하고 HTTP Header 조립은 Controller에 맡긴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentFileService {

    private final DocumentQueryService documentQueryService;
    private final FileStorageService fileStorageService;

    /**
     * 읽기 권한이 있는 문서의 현재 원본 파일과 안전한 응답 Metadata를 반환한다.
     *
     * <p>DB Snapshot의 파일 크기와 실제 Byte 수가 다르면 손상 또는 잘못된 저장 위치로 판단한다.
     */
    public DocumentFileDownload getDocumentFile(Long userId, Long documentId) {
        // 1. 짧은 DB Transaction에서 권한을 확인하고 현재 버전의 파일 위치를 Snapshot으로 고정한다.
        DocumentFileSnapshot snapshot = documentQueryService.getDocumentFileSnapshot(userId, documentId);

        // 2. DB Transaction이 끝난 뒤 원본 전체를 읽어 Controller가 소유할 수 있는 Byte 배열로 반환한다.
        byte[] content = fileStorageService.read(snapshot.storedFile());
        if (content.length != snapshot.fileSize()) {
            log.error("원본 파일 크기가 Metadata와 일치하지 않습니다. expected={}, actual={}",
                snapshot.fileSize(), content.length);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED);
        }
        return new DocumentFileDownload(
            content,
            snapshot.originalFilename(),
            snapshot.contentType(),
            snapshot.fileSize()
        );
    }
}
