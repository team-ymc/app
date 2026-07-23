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
    UPLOAD_NOT_FOUND(HttpStatus.CONFLICT),

    /** access token 없음·만료 (FT-001) */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** refresh 쿠키 없음·만료·폐기·재사용 (FT-001) */
    AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED),

    /** 인증됐지만 대상 논문 접근 권한 없음 (FT-001) */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 논문이 아직 채팅 가능한 상태가 아님 (FT-007) */
    PAPER_NOT_READY(HttpStatus.CONFLICT),

    /** 세션 없음 또는 현재 사용자·논문에 속하지 않음 (FT-007) */
    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 같은 세션에서 assistant 답변 생성 중 (FT-007) */
    CHAT_RUN_IN_PROGRESS(HttpStatus.CONFLICT),

    /** clientMessageId를 다른 요청 내용에 재사용함 (FT-007) */
    CLIENT_MESSAGE_ID_CONFLICT(HttpStatus.CONFLICT),

    /** 같은 clientMessageId·같은 content 재전송 — 기존 실행 상태 반환 (FT-007) */
    DUPLICATE_MESSAGE(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
