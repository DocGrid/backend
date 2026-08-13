package com.opensource.docgrid.global.exception;

import lombok.Getter;

@Getter
public class DocGridException extends RuntimeException {

    private final ErrorCode errorCode;

    public DocGridException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public DocGridException(ErrorCode errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public DocGridException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
