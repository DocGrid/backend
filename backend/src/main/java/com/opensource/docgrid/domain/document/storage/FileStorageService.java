package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

/**
 * 문서 도메인에 파일 저장·조회·삭제 기능을 제공하는 저장소 Port다.
 * Provider SDK와 경로 규칙은 Adapter 내부에 한정하고 호출자는 불변 저장 위치만 전달한다.
 */
public interface FileStorageService {

    StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey);

    /**
     * 저장된 Object 전체를 읽고 호출자와 Storage Stream 수명 주기를 분리한 Byte 배열을 반환한다.
     *
     * @param storedFile 읽을 Bucket과 Object Key
     * @return Object 전체 Byte
     */
    byte[] read(StoredFile storedFile);

    void delete(StoredFile storedFile);
}
