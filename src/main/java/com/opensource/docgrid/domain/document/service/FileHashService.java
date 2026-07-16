package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileHashService {

    private static final int BUFFER_SIZE = 8192;

    public String calculateSha256(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("파일 SHA-256 계산에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, e);
        }
    }
}
