package com.opensource.docgrid.global.common.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opensource.docgrid.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;

@Getter
public class ErrorResponse {

    private final boolean success = false;
    private final int status;
    private final String code;
    private final String message;
    private final String method;
    private final String path;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private final LocalDateTime timestamp = LocalDateTime.now();

    private ErrorResponse(int status, String code, String message, String method, String path) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.method = method;
        this.path = path;
    }

    public static ErrorResponse of(ErrorCode errorCode, HttpServletRequest request) {
        return new ErrorResponse(
            errorCode.getHttpStatus().value(),
            errorCode.getCode(),
            errorCode.getMessage(),
            request.getMethod(),
            request.getRequestURI()
        );
    }

    public static ErrorResponse of(ErrorCode errorCode, String customMessage, HttpServletRequest request) {
        return new ErrorResponse(
            errorCode.getHttpStatus().value(),
            errorCode.getCode(),
            customMessage,
            request.getMethod(),
            request.getRequestURI()
        );
    }
}
