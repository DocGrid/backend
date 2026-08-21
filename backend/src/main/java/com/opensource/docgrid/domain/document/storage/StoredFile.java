package com.opensource.docgrid.domain.document.storage;

import com.opensource.docgrid.domain.document.enums.StorageProvider;

/**
 * Provider에 독립적으로 저장된 파일의 논리 위치를 전달하는 불변 값이다.
 * 실제 endpoint나 Local 절대 경로는 포함하지 않아 실행 환경의 Adapter 경계를 유지한다.
 */
public record StoredFile(StorageProvider storageProvider, String bucketName, String objectKey) {
}
