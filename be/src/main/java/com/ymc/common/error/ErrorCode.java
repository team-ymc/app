package com.ymc.common.error;

import org.springframework.http.HttpStatus;

/**
 * 계약(project-docs/contracts/openapi.yaml `Error.code`)의 닫힌 enum 그대로.
 *
 * <p>새 코드가 필요하면 여기서 만들지 않는다 — openapi.yaml PR이 먼저다 (design D8).
 * 계약에 없는 5xx는 코드를 싣지 않고 Spring 기본 응답으로 내보낸다.
 */
public enum ErrorCode {

    /** 필수 필드 누락 등 잘못된 요청 */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    /** contentType이 application/pdf가 아님 */
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST),

    /** 같은 파일명의 논문이 이미 있음 (create) */
    DUPLICATE_FILENAME(HttpStatus.CONFLICT),

    /** 존재하지 않는 paperId */
    PAPER_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** S3에 객체가 없음 (complete) */
    UPLOAD_NOT_FOUND(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
