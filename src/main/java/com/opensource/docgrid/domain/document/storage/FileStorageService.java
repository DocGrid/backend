package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

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
