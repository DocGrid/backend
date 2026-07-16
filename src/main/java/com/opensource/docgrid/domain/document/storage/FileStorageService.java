package com.opensource.docgrid.domain.document.storage;

import java.io.InputStream;

public interface FileStorageService {

    StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey);

    void delete(StoredFile storedFile);
}
