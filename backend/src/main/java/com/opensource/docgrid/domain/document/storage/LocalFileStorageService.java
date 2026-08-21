package com.opensource.docgrid.domain.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 설정된 Local Root 아래에서 문서 원본을 저장·조회·삭제하는 파일 저장소 Adapter다.
 * DB에는 Host 절대 경로 대신 논리 Bucket과 Object Key만 전달하며 Root 밖 경로 접근을 차단한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path rootPath;
    private final String bucketName;

    public LocalFileStorageService(FileStorageProperties properties) {
        this.bucketName = requireBucket(properties.getBucket());
        this.rootPath = prepareRoot(properties.getLocal().getRoot());
    }

    @Override
    public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
        Path temporaryFile = null;
        try {
            // 1. Object Key의 부모를 준비하고 실제 경로가 설정 Root 안에 있는지 다시 확인한다.
            Path target = resolveWritablePath(objectKey);
            temporaryFile = Files.createTempFile(target.getParent(), ".docgrid-", ".tmp");

            // 2. 부분 파일이 최종 Key로 노출되지 않도록 임시 파일에 모두 쓴 뒤 교체한다.
            long copiedBytes = Files.copy(inputStream, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            if (copiedBytes != fileSize) {
                throw new IOException("저장된 파일 크기가 요청 Metadata와 일치하지 않습니다.");
            }
            moveAtomically(temporaryFile, target);
            temporaryFile = null;
            return new StoredFile(StorageProvider.LOCAL, bucketName, objectKey);
        } catch (Exception exception) {
            deleteTemporaryFile(temporaryFile);
            log.error("Local 파일 저장에 실패했습니다. objectKey={}", objectKey, exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    @Override
    public byte[] read(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            Path target = resolveExistingPath(storedFile.objectKey());
            return Files.readAllBytes(target);
        } catch (NoSuchFileException exception) {
            throw new DocGridException(ErrorCode.FILE_OBJECT_NOT_FOUND, exception);
        } catch (Exception exception) {
            log.error("Local 파일 읽기에 실패했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    @Override
    public void delete(StoredFile storedFile) {
        validateLocation(storedFile);
        try {
            Path target = resolvePath(storedFile.objectKey());
            if (!Files.exists(target)) {
                return;
            }
            validateExistingPath(target);
            Files.deleteIfExists(target);
        } catch (Exception exception) {
            log.error("Local 파일 삭제에 실패했습니다. objectKey={}", storedFile.objectKey(), exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    private Path prepareRoot(Path configuredRoot) {
        if (configuredRoot == null) {
            throw new IllegalStateException("storage.local.root 설정이 필요합니다.");
        }
        try {
            Path normalizedRoot = configuredRoot.toAbsolutePath().normalize();
            Files.createDirectories(normalizedRoot);
            return normalizedRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Local 파일 저장소 Root를 준비하지 못했습니다.", exception);
        }
    }

    private String requireBucket(String configuredBucket) {
        if (!StringUtils.hasText(configuredBucket)) {
            throw new IllegalStateException("storage.bucket 설정이 필요합니다.");
        }
        return configuredBucket;
    }

    private Path resolveWritablePath(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Object Key의 부모 경로를 확인할 수 없습니다.");
        }
        Files.createDirectories(parent);
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(rootPath)) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
        return realParent.resolve(target.getFileName());
    }

    private Path resolveExistingPath(String objectKey) throws IOException {
        Path target = resolvePath(objectKey);
        if (!Files.exists(target)) {
            throw new NoSuchFileException(target.toString());
        }
        validateExistingPath(target);
        return target;
    }

    private Path resolvePath(String objectKey) throws IOException {
        if (!StringUtils.hasText(objectKey)) {
            throw new IOException("Object Key가 비어 있습니다.");
        }
        Path relativePath = Path.of(objectKey).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
        Path target = rootPath.resolve(relativePath).normalize();
        if (!target.startsWith(rootPath)) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
        return target;
    }

    private void validateExistingPath(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Symbolic Link는 파일 저장 위치로 사용할 수 없습니다.");
        }
        Path realParent = target.getParent().toRealPath();
        if (!realParent.startsWith(rootPath)) {
            throw new IOException("Object Key가 Local 파일 저장소 Root를 벗어났습니다.");
        }
    }

    private void validateLocation(StoredFile storedFile) {
        if (storedFile.storageProvider() == StorageProvider.LOCAL
            && bucketName.equals(storedFile.bucketName())) {
            return;
        }
        log.error(
            "현재 Local 저장소 설정과 파일 위치가 일치하지 않습니다. storedProvider={}, storedBucket={}",
            storedFile.storageProvider(),
            storedFile.bucketName()
        );
        throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException cleanupException) {
            log.warn("Local 임시 파일 정리에 실패했습니다. path={}", temporaryFile, cleanupException);
        }
    }
}
