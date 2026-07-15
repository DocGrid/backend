package com.opensource.docgrid.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // COMMON
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON-001", "잘못된 요청입니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON-002", "요청 파라미터가 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-003", "리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-004", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-005", "지원하지 않는 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-006", "서버 내부 오류가 발생했습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-007", "인증이 필요합니다."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "COMMON-008", "데이터 충돌이 발생했습니다."),

    // USER
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-002", "이미 사용 중인 이메일입니다."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "USER-003", "비활성화된 계정입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "USER-004", "이메일 또는 비밀번호가 올바르지 않습니다."),

    // DEPARTMENT
    DEPARTMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "DEPT-001", "존재하지 않는 부서입니다."),

    // ROLE
    ROLE_NOT_FOUND(HttpStatus.BAD_REQUEST, "ROLE-001", "존재하지 않는 역할입니다."),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "ROLE-002", "접근 권한이 없습니다."),
    ROLE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "ROLE-003", "이미 부여된 역할입니다."),

    // COLLECTION
    COLLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLECTION-001", "컬렉션을 찾을 수 없습니다."),
    COLLECTION_DOCUMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "COLLECTION-002", "이미 컬렉션에 추가된 문서입니다."),

    // DOCUMENT
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "DOCUMENT-001", "문서를 찾을 수 없습니다."),

    // PERMISSION
    INVALID_TARGET_TYPE(HttpStatus.BAD_REQUEST, "PERMISSION-001", "target_type과 ID 필드 조합이 올바르지 않습니다."),
    COLLECTION_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-002", "컬렉션 권한을 찾을 수 없습니다."),
    DOCUMENT_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-003", "문서 권한을 찾을 수 없습니다."),

    // EMBEDDING MODEL
    EMBEDDING_MODEL_NOT_CONFIGURED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EMBEDDING-MODEL-001",
        "사용 가능한 임베딩 모델이 설정되지 않았습니다."
    ),
    MULTIPLE_ACTIVE_EMBEDDING_MODELS(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EMBEDDING-MODEL-002",
        "사용 가능한 임베딩 모델이 여러 개 설정되어 있습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
