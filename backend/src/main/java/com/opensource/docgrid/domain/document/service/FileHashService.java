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

/**
 * 업로드 파일을 스트리밍해 중복 판정과 무결성 확인에 사용할 SHA-256 Hash를 계산한다.
 *
 * <p>전체 파일을 메모리에 올리지 않으며 I/O 또는 알고리즘 초기화 실패를 문서 업로드 오류 계약으로 변환한다.
 */
@Slf4j
@Service
public class FileHashService {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Multipart 파일 전체 바이트의 소문자 16진수 SHA-256 값을 반환한다.
     */
    public String calculateSha256(MultipartFile file) {
        // 1. 요청 파일 Stream과 SHA-256 계산기를 함께 준비한다.
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 2. 고정 크기 Buffer로 끝까지 읽어 큰 파일도 일정한 메모리만 사용한다.
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            // 3. 최종 Digest를 저장·비교 가능한 고정 길이 16진수 문자열로 변환한다.
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            // 4. 구현 세부 예외는 업로드 계층의 단일 Hash 계산 실패 오류로 변환한다.
            log.error("파일 SHA-256 계산에 실패했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, e);
        }
    }
}
